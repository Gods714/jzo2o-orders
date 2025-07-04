package com.jzo2o.orders.manager.listener;

import com.alibaba.fastjson.JSON;
import com.jzo2o.common.constants.MqConstants;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.connection.SortParameters;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author Gods
 * @Date 2025/6/24 17:23
 * @description 为订单支付状态监听
 */

@Component
@Slf4j
public class TradeStatusListener {

    @Resource
    private IOrdersCreateService ordersCreateService;

    /**
     * 更新支付结果
     * 支付成功
     *
     * @param msg 消息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.Queues.ORDERS_TRADE_UPDATE_STATUS),
            exchange = @Exchange(name = MqConstants.Exchanges.TRADE, type = ExchangeTypes.TOPIC),
            key = MqConstants.RoutingKeys.TRADE_UPDATE_STATUS
    ))
    public void listenTradeUpdatePayStatusMsg(String msg) {
        log.info("监听家政服务支付结果消息：{}", msg);

        // 需要判断发送过来的消息是否为家政系统所需要的
        //将msg转为java对象
        List<TradeStatusMsg> tradeStatusMsgs = JSON.parseArray(msg, TradeStatusMsg.class);

        List<TradeStatusMsg> collect = tradeStatusMsgs
                .stream()
                .filter(tradeStatusMsg -> "jzo2o.orders".equals(tradeStatusMsg.getProductAppId()) && tradeStatusMsg.getStatusCode() == OrderPayStatusEnum.PAY_SUCCESS.getStatus())
                .collect(Collectors.toList());

        // 修改数据库信息
        for (TradeStatusMsg tradeStatusMsg : collect) {
            ordersCreateService.paySuccess(tradeStatusMsg);
        }


    }

}
