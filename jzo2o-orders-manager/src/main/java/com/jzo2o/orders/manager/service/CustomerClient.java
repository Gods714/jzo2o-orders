package com.jzo2o.orders.manager.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

import javax.annotation.Resource;

/**
 * @Author Gods
 * @Date 2025/6/20 21:54
 * @description 为统一的远程调用类
 */

@Component
@Slf4j
public class CustomerClient {

    @Resource
    private AddressBookApi bookAddrApi;

    @Resource
    private ServeApi serveApi;

    /**
     * 获取地址详情
     * @param id 地址id
     * @return 详细信息
     */
    // 指定value = 资源名称 , fallback  = 降级方法名 , blockHandler = 为熔断之后调用的方法
    @SentinelResource(value = "getAddressBookDetail", fallback = "detailFallback", blockHandler = "detailBlockHandler")
    public AddressBookResDTO getDetail(Long id){
        AddressBookResDTO detail = bookAddrApi.detail(id);
        return detail;

    }

    //执行异常走
    public AddressBookResDTO detailFallback(Long id, Throwable throwable) {
        log.error("非限流、熔断等导致的异常执行的降级方法，id:{},throwable:", id, throwable);
        return null;
    }

    //熔断后的降级逻辑
    public AddressBookResDTO detailBlockHandler(Long id, BlockException blockException) {
        log.error("触发限流、熔断时执行的降级方法，id:{},blockException:", id, blockException);
        return null;
    }



    /**
     * 获取服务详情
     * @param id 服务id
     * @return 详细信息
     */
    @SentinelResource(value = "getServeDetail", fallback = "findByIdFallback", blockHandler = "findByIdBlockHandler")
    public ServeAggregationResDTO findById(Long id){
        ServeAggregationResDTO serveApiById = serveApi.findById(id);
        return serveApiById;
    }

    public ServeAggregationResDTO findByIdFallback(Long id, Throwable throwable) {
        log.error("非限流、熔断等导致的异常执行的降级方法，id:{},throwable:", id, throwable);
        return null;
    }

    public ServeAggregationResDTO findByIdBlockHandler(Long id, BlockException blockException) {
        log.error("触发限流、熔断时执行的降级方法，id:{},blockException:", id, blockException);
        return null;
    }

}
