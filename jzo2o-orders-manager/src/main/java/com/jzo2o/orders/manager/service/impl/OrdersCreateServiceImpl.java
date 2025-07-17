package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.db.DbRuntimeException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseReqDTO;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.market.dto.response.CouponUseResDTO;
import com.jzo2o.api.trade.NativePayApi;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.constants.RedisConstants;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.client.CustomerClient;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.client.MarketClient;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {

    @Resource
    private CustomerClient customerClient;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private OrdersCreateServiceImpl own;

    @Resource
    private TradeProperties tradeProperties;

    @Resource
    private ServeApi serveApi;

    @Resource
    private NativePayApi nativePayApi;

    @Resource
    private TradingApi tradingApi;

    @Resource
    private OrderStateMachine orderStateMachine;

    @Resource
    private MarketClient marketClient;

    @Resource
    private CouponApi couponApi;


    /**
     * 生成订单id 格式：{yyMMdd}{13位id}
     *
     * @return 订单号
     */
    private Long generateOrderId() {

        // 使用redis的自增来实现
        Long increment = redisTemplate.opsForValue().increment(RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR, 1);

        // 获取时间格式
        String time = DateUtil.format(new Date(), "yyMMdd");

        long orderId = Long.parseLong(time) * 10000000000000L + increment;
        return orderId;
    }


    /**
     * 获取可用优惠券
     *
     * @param serveId 服务id
     * @param purNum  购买数量
     * @return 可用优惠券
     */
    @Override
    public List<AvailableCouponsResDTO> getAvailableCoupons(Long serveId, Integer purNum) {
        // 获取订单的总金额
        ServeAggregationResDTO serveApiById = serveApi.findById(serveId);
        if (ObjectUtils.isNull(serveApiById) || serveApiById.getSaleStatus() != 2) {
            throw new CommonException("服务不可用");
        }

        // 获取订单的总金额
        BigDecimal totalAmount = serveApiById.getPrice().multiply(new BigDecimal(purNum));


        //获取可用优惠券
        List<AvailableCouponsResDTO> available = marketClient.getAvailable(totalAmount);
        return available;
    }

    /**
     * 下单
     *
     * @param placeOrderReqDTO 为下单参数
     * @return 订单号
     */
    @Override
    public PlaceOrderResDTO place(PlaceOrderReqDTO placeOrderReqDTO) {
        // 校验服务地址
        AddressBookResDTO detail = customerClient.getDetail(placeOrderReqDTO.getAddressBookId());

        // 服务
        ServeAggregationResDTO serveResDTO = customerClient.findById(placeOrderReqDTO.getServeId());


        // 构建订单需要的信息 订单号等
        // 2.下单前数据准备
        Orders orders = new Orders();
        // id 订单id
        orders.setId(generateOrderId());
        // userId，从threadLocal获取当前登录用户的id，通过UserContextInteceptor拦截进行设置
        orders.setUserId(UserContext.currentUserId());
        // 服务id
        orders.setServeId(placeOrderReqDTO.getServeId());
        // 服务项id
        orders.setServeItemId(serveResDTO.getServeItemId());
        orders.setServeItemName(serveResDTO.getServeItemName());
        orders.setServeItemImg(serveResDTO.getServeItemImg());
        orders.setUnit(serveResDTO.getUnit());
        //服务类型信息
        orders.setServeTypeId(serveResDTO.getServeTypeId());
        orders.setServeTypeName(serveResDTO.getServeTypeName());
        // 订单状态
        orders.setOrdersStatus(0);
        // 支付状态，暂不支持，初始化一个空状态
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());
        // 服务时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        // 城市编码
        orders.setCityCode(serveResDTO.getCityCode());
        // 地理位置
        orders.setLon(detail.getLon());
        orders.setLat(detail.getLat());

        String serveAddress = new StringBuffer(detail.getProvince())
                .append(detail.getCity())
                .append(detail.getCounty())
                .append(detail.getAddress())
                .toString();
        orders.setServeAddress(serveAddress);
        // 联系人
        orders.setContactsName(detail.getName());
        orders.setContactsPhone(detail.getPhone());

        // 价格
        orders.setPrice(serveResDTO.getPrice());
        // 购买数量
        orders.setPurNum(NumberUtils.null2Default(placeOrderReqDTO.getPurNum(), 1));
        // 订单总金额 价格 * 购买数量
        orders.setTotalAmount(orders.getPrice().multiply(new BigDecimal(orders.getPurNum())));

        // 优惠金额 当前默认0
        orders.setDiscountAmount(BigDecimal.ZERO);
        // 实付金额 订单总金额 - 优惠金额
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));
        //排序字段,根据服务开始时间转为毫秒时间戳+订单后5位
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orders.getId() % 100000;
        orders.setSortBy(sortBy);
        // 核销优惠券
        if (ObjectUtils.isNotNull(placeOrderReqDTO.getCouponId())) {
            // 核销优惠券
            own.addWithCoupon(orders, placeOrderReqDTO.getCouponId());
        } else {
            // 未核销优惠券
            //保存订单
            own.add(orders);
        }

        return new PlaceOrderResDTO(orders.getId());
    }

    /**
     * 使用优惠券下单
     *
     * @param orders   订单信息
     * @param couponId 优惠券id
     */
//    @Transactional(rollbackFor = Exception.class)
    // 开启全局事物
    @GlobalTransactional
    public void addWithCoupon(Orders orders, Long couponId) {

        CouponUseReqDTO couponUseReqDTO = new CouponUseReqDTO();
        couponUseReqDTO.setOrdersId(orders.getId());
        couponUseReqDTO.setId(couponId);
        couponUseReqDTO.setTotalAmount(orders.getTotalAmount());

        // 返回的为优惠金额
        CouponUseResDTO resDTO = couponApi.use(couponUseReqDTO);

        // 优惠金额 当前默认0
        orders.setDiscountAmount(resDTO.getDiscountAmount());
        // 实付金额 订单总金额 - 优惠金额
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));

        boolean save = own.save(orders);
        if (!save) {
            throw new CommonException("使用优惠券下单失败");
        }
//        int i = 1/0;
    }

    /**
     * 请求支付服务
     *
     * @param id              订单id
     * @param ordersPayReqDTO 支付参数
     * @return 支付结果
     */
    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {
        // 判断当前的订单是否存在
        Orders orders = getById(id);

        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }

        // 当前的订单的支付状态是否为已支付
        Integer payStatus = orders.getPayStatus();
        if (payStatus == OrderPayStatusEnum.PAY_SUCCESS.getStatus() && ObjectUtils.isNotNull(orders.getTradingOrderNo())) {
            // 表示订单已支付
            log.info("订单已支付，订单号：{}", id);
            // 构造返回信息
            OrdersPayResDTO ordersPayResDTO = BeanUtils.copyBean(orders, OrdersPayResDTO.class);
            // 需要手动封装业务系统订单号 ,为orders的主键id
            ordersPayResDTO.setProductOrderNo(id);
            return ordersPayResDTO;
        } else {
            // 订单未支付
            // 需要请求远程服务来获取到支付的二维码
            NativePayResDTO nativePayResDTO = generateQrCode(orders, ordersPayReqDTO);
            OrdersPayResDTO ordersPayResDTO = BeanUtils.copyBean(nativePayResDTO, OrdersPayResDTO.class);

            // 获取信息成功,需要向数据库中修改orders信息
            boolean update = lambdaUpdate().eq(Orders::getId, orders.getId())
                    .set(Orders::getTradingOrderNo, nativePayResDTO.getTradingOrderNo())
                    .set(Orders::getTradingChannel, ordersPayReqDTO.getTradingChannel().getValue())
                    .update();
            if (!update) {
                log.error("更新订单信息失败，订单号：{}", id);
                throw new CommonException("更新订单信息失败");
            }
            return ordersPayResDTO;
        }
    }

    /**
     * 获取支付结果
     *
     * @param id 订单id
     * @return 支付结果
     */
    @Override
    public OrdersPayResDTO getPayResultFromTradServer(Long id) {
        // 判断当前的订单是否存在
        Orders orders = getById(id);

        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        // 判断当前的订单的支付状态是否为已支付,如果为未支付需要远程调用支付服务
        // 获取订单的pay_status状态
        Integer payStatus = orders.getPayStatus();
        // 判断
        if (payStatus == OrderPayStatusEnum.NO_PAY.getStatus() && ObjectUtils.isNotNull(orders.getTradingOrderNo())) {
            // 表示订单未支付
            // 需要调用远程服务来获取支付结果
            TradingResDTO tradingResDTO = tradingApi.findTradResultByTradingOrderNo(orders.getTradingOrderNo());

            // 需要判断订单支付结果
            if (ObjectUtils.equal(tradingResDTO.getTradingState(), TradingStateEnum.YJS)) {
                // 这里表示支付成功 修改数据库信息
                TradeStatusMsg msg = TradeStatusMsg.builder()
                        .productOrderNo(orders.getId())
                        .tradingChannel(tradingResDTO.getTradingChannel())
                        .statusCode(TradingStateEnum.YJS.getCode())
                        .tradingOrderNo(tradingResDTO.getTradingOrderNo())
                        .transactionId(tradingResDTO.getTransactionId())
                        .build();
                own.paySuccess(msg);

                // 构建返回对象
                OrdersPayResDTO ordersPayResDTO = BeanUtils.copyBean(tradingResDTO, OrdersPayResDTO.class);
                // 需要单独设置支付状态
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }
        }

        // 这里需要返回数据库查询到的信息
        OrdersPayResDTO ordersPayResDTO = BeanUtils.copyBean(orders, OrdersPayResDTO.class);
        ordersPayResDTO.setProductOrderNo(orders.getId());
        return ordersPayResDTO;
    }


    /**
     * 支付成功,,修改数据库信息
     *
     * @param tradeStatusMsg 交易状态消息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        // 为修改数据库信息,此方法为公用方法,需要再次判断订单的准确性
        // 判断当前的订单是否存在
        Orders orders = getById(tradeStatusMsg.getProductOrderNo());

        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }

        // 判断订单的状态,只能为待支付
        if (!ObjectUtils.equal(orders.getOrdersStatus(), OrderStatusEnum.NO_PAY.getStatus())) {
            throw new CommonException("订单状态不为待支付");
        }

        //第三方支付单号校验
        if (ObjectUtil.isEmpty(tradeStatusMsg.getTransactionId())) {
            throw new CommonException("支付成功通知缺少第三方支付单号");
        }

//        //修改数据库信息
//        boolean update = lambdaUpdate()
//                .eq(Orders::getId, tradeStatusMsg.getProductOrderNo())
//                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())
//                //订单状态
//                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())
//                .set(Orders::getTradingOrderNo, tradeStatusMsg.getTradingOrderNo())
//                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())
//                .set(Orders::getTradingChannel, tradeStatusMsg.getTradingChannel())
//                .set(Orders::getPayTime, LocalDateTime.now())
//                .update();
//        if (!update) {
//            log.error("更新订单信息失败，订单号：{}", tradeStatusMsg.getProductOrderNo());
//            throw new CommonException("更新订单信息失败");
//        }

        // 使用状态机来实现修改的订单的状态
        // 参数 Long dbShardId, String bizId, StatusChangeEvent statusChangeEventEnum, T bizSnapshot
        //  注意添加快照信息
        OrderSnapshotDTO orderSnapshotDTO = new OrderSnapshotDTO();
        // 订单id
        orderSnapshotDTO.setId(orders.getId());
        // 支付单号
        orderSnapshotDTO.setTradingOrderNo(tradeStatusMsg.getTradingOrderNo());
        // 支付时间
        orderSnapshotDTO.setPayTime(LocalDateTime.now());
        // 三方流水
        orderSnapshotDTO.setThirdOrderId(tradeStatusMsg.getTransactionId());
        // 支付渠道
        orderSnapshotDTO.setTradingChannel(tradeStatusMsg.getTradingChannel());
        // 需要查询到订单信息

        orderStateMachine.changeStatus(orders.getUserId(), tradeStatusMsg.getProductOrderNo().toString(), OrderStatusChangeEventEnum.PAYED, orderSnapshotDTO);


    }

    /**
     * 查询超时未支付的订单
     *
     * @param count 查询数量
     * @return 订单列表
     */
    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {


        List<Orders> ordersList = lambdaQuery()
                .eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())
                // 订单的时间要小于当前时间-15分钟
                .lt(Orders::getCreateTime, LocalDateTime.now().minusMinutes(15))
                .last("limit " + count)
                .list();

        return ordersList;


    }

    /**
     * 生成二维码
     *
     * @param orders          订单信息
     * @param ordersPayReqDTO 支付参数
     */
    private NativePayResDTO generateQrCode(Orders orders, OrdersPayReqDTO ordersPayReqDTO) {
        //来构建远程调用的参数
        NativePayReqDTO nativePayReqDTO = new NativePayReqDTO();
        // 获取到当前订单的支付渠道
        String tradingChannel = orders.getTradingChannel();
        if (ObjectUtils.isNotNull(tradingChannel) && ObjectUtil.notEqual(orders.getTradingChannel(), ordersPayReqDTO.getTradingChannel())) {
            // 这里表示修改支付渠道
            nativePayReqDTO.setChangeChannel(true);
        }
        //为商户号
        Long merchantId = ObjectUtils.notEqual(ordersPayReqDTO.getTradingChannel().getValue(), PayChannelEnum.ALI_PAY.getValue()) ? tradeProperties.getWechatEnterpriseId() : tradeProperties.getAliEnterpriseId();
        nativePayReqDTO.setEnterpriseId(merchantId);
        // 为备注信息
        nativePayReqDTO.setMemo(orders.getServeItemName());
        // 系统业务标识
        nativePayReqDTO.setProductAppId("jzo2o.orders");
        //业务系统订单号
        nativePayReqDTO.setProductOrderNo(orders.getId());
        //支付渠道
        nativePayReqDTO.setTradingChannel(ordersPayReqDTO.getTradingChannel());
        //交易金额
        nativePayReqDTO.setTradingAmount(orders.getRealPayAmount());

        NativePayResDTO downLineTrading = nativePayApi.createDownLineTrading(nativePayReqDTO);
        // 判断
        if (ObjectUtils.isNull(downLineTrading)) {
            log.info("获取支付二维码失败");
            throw new CommonException("获取支付二维码失败");
        }
        return downLineTrading;
    }


    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        boolean save = this.save(orders);
        if (!save) {
            throw new DbRuntimeException("下单失败");
        }

        // 这里要启动状态机
        // 参数 Long dbShardId, String bizId, StatusDefine statusDefine, T bizSnapshot
        // 注意事物 getById(orders.getId()) 这里需要再次的查询
        OrderSnapshotDTO orderSnapshotDTO = BeanUtils.copyBean(getById(orders.getId()), OrderSnapshotDTO.class);
        orderStateMachine.start(orders.getUserId(), orders.getId().toString(), OrderStatusEnum.NO_PAY, orderSnapshotDTO);
    }
}
