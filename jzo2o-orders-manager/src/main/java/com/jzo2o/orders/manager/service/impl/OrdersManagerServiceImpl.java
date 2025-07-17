package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseBackReqDTO;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.common.utils.JsonUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
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
import com.jzo2o.redis.helper.CacheHelper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;
import static com.jzo2o.orders.base.constants.RedisConstants.RedisKey.ORDERS;

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

    @Resource
    private OrderStateMachine orderStateMachine;

    @Resource
    private CacheHelper cacheHelper;

    @Resource
    private CouponApi couponApi;


    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids);
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
                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
                // 只需查询到id
                .select(Orders::getId);
        Page<Orders> queryPage = new Page<>();
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.查询订单列表
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        List<Orders> records = ordersPage.getRecords();
        // 获取到订单id
        List<Long> fieldValues = CollUtils.getFieldValues(records, Orders::getId);

       // 根据订单id查询,为聚集索引
        //参数1：redisKey的一部分
        String redisKey = String.format(ORDERS, currentUserId);
//        List<Orders> ordersList = batchQuery(fieldValues);
        // 使用缓存 ,为项目自定义的cacheHelper缓存类
        // String dataType, List<K> objectIds, BatchDataQueryExecutor<K, T> batchDataQueryExecutor, Class<T> clazz, Long ttl
        List<OrderSimpleResDTO> orderSimpleResDTOS = cacheHelper.batchGet(redisKey, fieldValues, (dbNoIds, classType) -> {
            //dbNoIds 为缓存中没有的id
            //查询数据库
            List<Orders> orders = batchQuery(dbNoIds);
            if (CollUtils.isEmpty(orders)) {
                //为了防止缓存穿透返回空数据
                return new HashMap<>();
            }
            // classType返回的类型
            List<OrderSimpleResDTO> ordersList = BeanUtils.copyToList(orders, classType);

            // 返回的结果为map
            Map<Long, OrderSimpleResDTO> collected = ordersList.stream().collect(Collectors.toMap(OrderSimpleResDTO::getId, v -> v));

            return collected;
        }, OrderSimpleResDTO.class, 6000L);
//        List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(ordersList, OrderSimpleResDTO.class);
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
//        Orders orders = queryById(id);
        // 使用快照来实现优化
        String currentSnapshotCache = orderStateMachine.getCurrentSnapshotCache(id.toString());
        // 转化成对象
//        OrderSnapshotDTO orderSnapshotDTO = BeanUtils.toBean(currentSnapshotCache, OrderSnapshotDTO.class);
        OrderSnapshotDTO orderSnapshotDTO = JsonUtils.toBean(currentSnapshotCache, OrderSnapshotDTO.class);

        // 添加一步,实现懒加载
        orderSnapshotDTO = canalIfPayOvertime(orderSnapshotDTO);
        OrderResDTO orderResDTO = BeanUtil.toBean(orderSnapshotDTO, OrderResDTO.class);
        return orderResDTO;
    }

    /**
     * 订单取消懒加载的实现
     *
     * @return 订单信息
     */
    public OrderSnapshotDTO canalIfPayOvertime(OrderSnapshotDTO orderSnapshotDTO) {
        // 判断订单是否超时
        // 取出订单的orders_status
        Integer ordersStatus = orderSnapshotDTO.getOrdersStatus();
        if (ordersStatus.equals(OrderStatusEnum.NO_PAY.getStatus()) && orderSnapshotDTO.getCreateTime().plusMinutes(15).isBefore(LocalDateTime.now())) {
            // 订单超时
            // 严谨一些,我们需要判断当前订单最新的支付状态
            OrdersPayResDTO resultFromTradServer = ordersCreateService.getPayResultFromTradServer(orderSnapshotDTO.getId());
            if (ObjectUtils.isNotNull(resultFromTradServer) && resultFromTradServer.getPayStatus() != OrderPayStatusEnum.PAY_SUCCESS.getStatus()) {
//                // 修改订单信息
//                OrderCancelDTO orderCancelDTO = new OrderCancelDTO();
//                orderCancelDTO.setId(orderSnapshotDTO.getId());
//                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
//                orderCancelDTO.setCancelReason("支付超时未支付,系统自动取消");
//                cancel(orderCancelDTO);
//                // 查询最新的订单信息返回
//                orders = getById(orders.getId());
//                return orders;
                // 使用状态机来实现
                orderStateMachine.changeStatus(orderSnapshotDTO.getUserId(), orderSnapshotDTO.getId().toString(), OrderStatusChangeEventEnum.CANCEL, orderSnapshotDTO);
                String currentSnapshot = orderStateMachine.getCurrentSnapshot(orderSnapshotDTO.getId().toString());
                OrderSnapshotDTO orderSnapshotDTO1 = BeanUtils.toBean(currentSnapshot, OrderSnapshotDTO.class);
                return orderSnapshotDTO1;
            }
        }
        return orderSnapshotDTO;
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
        // 设置用户id
        orderCancelDTO.setUserId(orders.getUserId());

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
    @GlobalTransactional(rollbackFor = Exception.class)
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

        // 根据订单id查询是否使用优惠券
        AvailableCouponsResDTO info = couponApi.getInfo(orderCancelDTO.getId());
        if (ObjectUtils.isNotNull(info)){
            CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
            couponUseBackReqDTO.setId(info.getId());
            couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
            couponUseBackReqDTO.setUserId(orderCancelDTO.getUserId());
            couponApi.useBack(couponUseBackReqDTO);
        }

    }

    /**
     * 订单取消
     *
     * @param orderCancelDTO 取消参数
     */
    @GlobalTransactional(rollbackFor = Exception.class)
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

        // 修改订单状态 todo 实现使用状态机来管理
//        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
//                .id(orderCancelDTO.getId())
//                .originStatus(OrderStatusEnum.NO_PAY.getStatus())
//                .targetStatus(OrderStatusEnum.CANCELED.getStatus())
//                .build();
//        Integer i = ordersCommonService.updateStatus(orderUpdateStatusDTO);
//        if (i <= 0) {
//            throw new CommonException("订单状态修改失败");
//        }
        OrderSnapshotDTO orderSnapshotDTO = BeanUtils.copyBean(orderCancelDTO, OrderSnapshotDTO.class);

        orderStateMachine.changeStatus(orderCancelDTO.getUserId(), orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CANCEL, orderSnapshotDTO);

        // 根据订单id查询是否使用优惠券
        AvailableCouponsResDTO info = couponApi.getInfo(orderCancelDTO.getId());
        if (ObjectUtils.isNotNull(info)){
            CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
            couponUseBackReqDTO.setId(info.getId());
            couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
            couponUseBackReqDTO.setUserId(orderCancelDTO.getUserId());
            couponApi.useBack(couponUseBackReqDTO);
        }

    }

}
