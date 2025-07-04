package com.jzo2o.orders.base.config;

import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.statemachine.AbstractStateMachine;
import com.jzo2o.statemachine.core.StatusDefine;
import com.jzo2o.statemachine.persist.StateMachinePersister;
import com.jzo2o.statemachine.snapshot.BizSnapshotService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * @Author Gods
 * @Date 2025/7/2 20:19
 * @description 定义订单状态机类
 */

@Component
public class OrderStateMachine extends AbstractStateMachine<OrderSnapshotDTO> {
    protected OrderStateMachine(StateMachinePersister stateMachinePersister, BizSnapshotService bizSnapshotService, RedisTemplate redisTemplate) {
        super(stateMachinePersister, bizSnapshotService, redisTemplate);
    }



    @Override
    protected String getName() {
        return "order";
    }

    @Override
    protected void postProcessor(OrderSnapshotDTO bizSnapshot) {

    }

    @Override
    protected StatusDefine getInitState() {
        return OrderStatusEnum.NO_PAY;
    }
}
