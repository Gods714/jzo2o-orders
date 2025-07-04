package com.jzo2o.orders.base.handler;

import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.statemachine.core.StatusChangeEvent;
import com.jzo2o.statemachine.core.StatusChangeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 订单支付成功处理器
 *
 * @author itcast
 * @create 2023/8/17 18:08
 **/
@Slf4j
@Component("order_payed")
public class OrderPayedHandler implements StatusChangeHandler<OrderSnapshotDTO> {

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Override
    public void handler(String bizId, StatusChangeEvent statusChangeEventEnum, OrderSnapshotDTO bizSnapshot) {
        log.info("订单支付成功处理器");
        // 实现对订单状态的修改
        // 构建订单修改条件
        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder()
                .id(bizSnapshot.getId())
                .payTime(bizSnapshot.getPayTime())
                .originStatus(OrderStatusEnum.NO_PAY.getStatus())
                .targetStatus(OrderStatusEnum.DISPATCHING.getStatus())
                .tradingOrderNo(bizSnapshot.getTradingOrderNo())
                .tradingChannel(bizSnapshot.getTradingChannel())
                .transactionId(bizSnapshot.getThirdOrderId()).build();

        Integer i = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (i < 1) {
            log.error("订单状态修改失败");
            throw new CommonException("订单状态修改失败");
        }

    }
}
