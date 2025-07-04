package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.handler.OrdersHandler;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;

/**
 * <p>
 * 订单表 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersManagerServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersManagerService {

    @Resource
    private OrdersManagerServiceImpl owner;

    @Resource
    private IOrdersCanceledService ordersCanceledService;

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Resource
    private IOrdersCreateService ordersCreateService;

    @Resource
    private IOrdersRefundService ordersRefundService;

    @Resource
    private OrdersHandler ordersHandler;


    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids).ge(Orders::getUserId, 0);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public Orders queryById(Long id) {
        return baseMapper.selectById(id);
    }

    /**
     * 滚动分页查询
     *
     * @param currentUserId 当前用户id
     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
     * @param sortBy        排序字段
     * @return 订单列表
     */
    @Override
    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
        //1.构件查询条件
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
                .eq(Orders::getUserId, currentUserId)
                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus());
        Page<Orders> queryPage = new Page<>();
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.查询订单列表
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        List<Orders> records = ordersPage.getRecords();
        List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(records, OrderSimpleResDTO.class);
        return orderSimpleResDTOS;

    }

    /**
     * 根据订单id查询
     *
     * @param id 订单id
     * @return 订单详情
     */
    @Override
    public OrderResDTO getDetail(Long id) {
        Orders orders = queryById(id);
        // 添加一步,实现懒加载
        orders = canalIfPayOvertime(orders);
        OrderResDTO orderResDTO = BeanUtil.toBean(orders, OrderResDTO.class);
        return orderResDTO;
    }

    /**
     * 订单取消懒加载的实现
     *
     * @return 订单信息
     */
    public Orders canalIfPayOvertime(Orders orders) {
        // 判断订单是否超时
        // 取出订单的orders_status
        Integer ordersStatus = orders.getOrdersStatus();
        if (ordersStatus.equals(OrderStatusEnum.NO_PAY.getStatus()) && orders.getCreateTime().plusMinutes(15).isBefore(LocalDateTime.now())) {
            // 订单超时
            // 严谨一些,我们需要判断当前订单最新的支付状态
            OrdersPayResDTO resultFromTradServer = ordersCreateService.getPayResultFromTradServer(orders.getId());
            if (ObjectUtils.isNotNull(resultFromTradServer) && resultFromTradServer.getPayStatus() != OrderPayStatusEnum.PAY_SUCCESS.getStatus()) {
                // 修改订单信息
                OrderCancelDTO orderCancelDTO = new OrderCancelDTO();
                orderCancelDTO.setId(orders.getId());
                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
                orderCancelDTO.setCancelReason("支付超时未支付,系统自动取消");
                cancel(orderCancelDTO);
                // 查询最新的订单信息返回
                orders = getById(orders.getId());
                return orders;
            }
        }
        return orders;
    }


    /**
     * 订单评价
     *
     * @param ordersId 订单id
     */
    @Override
    @Transactional
    public void evaluationOrder(Long ordersId) {
//        //查询订单详情
//        Orders orders = queryById(ordersId);
//
//        //构建订单快照
//        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
//                .evaluationTime(LocalDateTime.now())
//                .build();
//
//        //订单状态变更
//        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.EVALUATE, orderSnapshotDTO);
    }

    /**
     * 取消订单
     *
     * @param orderCancelDTO 取消订单模型
     */
    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        // 实现取消订单,判断是否为已支付和是否存在

        // 获取订单id
        Long id = orderCancelDTO.getId();
        Orders orders = getById(id);
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        // 设置订单的支付服务交易单号
        orderCancelDTO.setTradingOrderNo(orders.getTradingOrderNo());
        // 实际支付金额
        orderCancelDTO.setRealPayAmount(orders.getRealPayAmount());


        // 获取订单的支付状态
        Integer ordersStatus = orders.getOrdersStatus();
        if (ordersStatus.equals(OrderStatusEnum.NO_PAY.getStatus())) {
            // 表示订单未支付
            owner.cancelByNoPay(orderCancelDTO);

        } else if (ordersStatus.equals(OrderStatusEnum.DISPATCHING.getStatus())) {
            // 订单已支付
            owner.cancelByDispatching(orderCancelDTO);
            // 单独开启一个线程来处理退款
            ordersHandler.requestRefundNewThread(orderCancelDTO.getId());
        } else {
            // 系统目前的业务逻辑,只支持上述两种状态
            throw new CommonException("订单状态不支持退单");

        }

    }

    //派单中状态取消订单
    @Transactional(rollbackFor = Exception.class)
    public void cancelByDispatching(OrderCancelDTO orderCancelDTO) {
        // 需要向订单取消表插入数据
        // 构建插入数据
        OrdersCanceled ordersCanceled = BeanUtils.copyBean(orderCancelDTO, OrdersCanceled.class);
        // 取消人id
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        // 取消人名称
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        // 取消人类型
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        // 取消时间
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);

        // 修改订单状态
        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
                .id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.DISPATCHING.getStatus())
                .targetStatus(OrderStatusEnum.CLOSED.getStatus())
                .refundStatus(OrderRefundStatusEnum.REFUNDING.getStatus())
                .build();
        Integer i = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (i <= 0) {
            throw new CommonException("订单状态修改失败");
        }

        // 向订单取消记录表中插入数据
        OrdersRefund ordersRefund = BeanUtils.toBean(orderCancelDTO, OrdersRefund.class);
        boolean save = ordersRefundService.save(ordersRefund);
        if (!save) {
            log.info("向订单取消记录表中插入数据失败,订单id为: {}", orderCancelDTO.getId());
            throw new CommonException("向订单取消记录表中插入数据失败");
        }

    }

    /**
     * 订单取消
     *
     * @param orderCancelDTO 取消参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByNoPay(OrderCancelDTO orderCancelDTO) {
        // 需要向订单取消表插入数据
        // 构建插入数据
        OrdersCanceled ordersCanceled = BeanUtils.copyBean(orderCancelDTO, OrdersCanceled.class);
        // 取消人id
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        // 取消人名称
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        // 取消人类型
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        // 取消时间
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);

        // 修改订单状态
        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
                .id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.NO_PAY.getStatus())
                .targetStatus(OrderStatusEnum.CANCELED.getStatus())
                .build();
        Integer i = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (i <= 0) {
            throw new CommonException("订单状态修改失败");
        }


    }

}
