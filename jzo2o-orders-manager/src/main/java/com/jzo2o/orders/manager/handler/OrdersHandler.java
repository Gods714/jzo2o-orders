package com.jzo2o.orders.manager.handler;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.trade.RefundRecordApi;
import com.jzo2o.api.trade.dto.response.ExecutionResultResDTO;
import com.jzo2o.api.trade.enums.RefundStatusEnum;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * @Author Gods
 * @Date 2025/6/25 16:08
 * @description 为订单超时未支付自动取消订单定时任务类
 */


@Component
@Slf4j
public class OrdersHandler {

    @Resource
    private IOrdersCreateService ordersCreateService;

    @Resource
    private IOrdersManagerService ordersManagerService;

    @Resource
    private IOrdersRefundService ordersRefundService;

    @Resource
    private RefundRecordApi refundRecordApi;

    @Resource
    private OrdersHandler ordersHandler;

    @Resource
    private OrdersMapper ordersMapper;


    @XxlJob("cancelOverTimePayOrder")
    public void cancelOverTimePayOrder() {
        log.info("开始执行定时任务：取消订单超时未支付");
        // 查询支付超时的订单
        List<Orders> orders = ordersCreateService.queryOverTimePayOrdersListByCount(100);
        // 判断是否为空
        if (CollUtil.isEmpty(orders)) {
            // 为空则返回
            log.info("没有超时未支付的订单");
            return;

        }
        // 遍历订单
        for (Orders order : orders) {
            // 创建订单取消参数
            OrderCancelDTO orderCancelDTO = BeanUtils.copyBean(order, OrderCancelDTO.class);
            // 封装数据
            orderCancelDTO.setCancelReason("支付超时未支付,系统自动取消");
            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);

            // 调用取消订单方法
            ordersManagerService.cancel(orderCancelDTO);
        }


    }

    /**
     * 订单退款异步任务
     */
    @XxlJob(value = "handleRefundOrders")
    public void handleRefundOrders() {
        // 获取订单退款列表
        List<OrdersRefund> ordersRefunds = ordersRefundService.queryRefundOrderListByCount(100);

        // 判断一下
        if (CollUtil.isEmpty(ordersRefunds)) {
            log.info("没有需要处理的退款订单");
            return;
        }

        // 遍历订单,请求退款
        for (OrdersRefund ordersRefund : ordersRefunds) {
            //请求退款
            requestRefundOrder(ordersRefund);
        }
    }

    /**
     * 请求退款
     *
     * @param ordersRefund 退款记录
     */
    private void requestRefundOrder(OrdersRefund ordersRefund) {

        ExecutionResultResDTO executionResultResDTO = null;

        try {
            executionResultResDTO = refundRecordApi.refundTrading(ordersRefund.getTradingOrderNo(), ordersRefund.getRealPayAmount());
        } catch (Exception e) {
            e.getMessage();
        }


        if (ObjectUtils.isNull(executionResultResDTO)) {
            // 退款结果为空
            log.info("退款结果为空");
            return;
        }

        //  获取退款结果 修改订单状态
        Integer refundStatus = executionResultResDTO.getRefundStatus();
        if (refundStatus != OrderRefundStatusEnum.REFUNDING.getStatus()) {
            // 退款结果不是退款中
            // 修改订单状态
            ordersHandler.refundOrder(ordersRefund, executionResultResDTO);
        }


    }

    /**
     * 修改订单状态
     *
     * @param ordersRefund          退款记录
     * @param executionResultResDTO 退款结果
     */
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(OrdersRefund ordersRefund, ExecutionResultResDTO executionResultResDTO) {
        // 为初始化状态
        int refundingStatus = OrderRefundStatusEnum.REFUNDING.getStatus();

        if (executionResultResDTO.getRefundStatus() == OrderRefundStatusEnum.REFUND_SUCCESS.getStatus()) {
            refundingStatus = OrderRefundStatusEnum.REFUND_SUCCESS.getStatus();
        } else if (executionResultResDTO.getRefundStatus() == OrderRefundStatusEnum.REFUND_FAIL.getStatus()) {
            refundingStatus = OrderRefundStatusEnum.REFUND_FAIL.getStatus();
        }

        // 判断refundingStatus
        if (refundingStatus == OrderRefundStatusEnum.REFUNDING.getStatus()) {
            return;
        }

        // 修改订单
        LambdaUpdateWrapper<Orders> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Orders::getId, ordersRefund.getId());
        updateWrapper.ne(Orders::getRefundStatus, refundingStatus);
        updateWrapper.set(Orders::getRefundStatus, refundingStatus)
                .set(Orders::getRefundId, executionResultResDTO.getRefundId())
                .set(Orders::getRefundNo, executionResultResDTO.getRefundNo());


        int update = ordersMapper.update(null, updateWrapper);
        if (update > 0) {
            log.info("修改订单状态成功");
            // 这里需要删除订单退款记录
            ordersRefundService.removeById(ordersRefund.getId());

        }


    }

    /**
     * 新启动一个线程请求退款
     *
     * @param ordersRefundId 为订单id
     */
    public void requestRefundNewThread(Long ordersRefundId) {

        new Thread(() -> {
            // 获取订单退款记录
            OrdersRefund ordersRefundServiceById = ordersRefundService.getById(ordersRefundId);
            if (ObjectUtils.isNotNull(ordersRefundServiceById)) {
                // 请求退款
                requestRefundOrder(ordersRefundServiceById);
            }


        }).start();


    }

}
