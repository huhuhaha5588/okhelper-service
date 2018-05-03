package com.ok.okhelper.service.impl;

import com.ok.okhelper.dao.*;
import com.ok.okhelper.exception.IllegalException;
import com.ok.okhelper.exception.NotFoundException;
import com.ok.okhelper.pojo.dto.DeliveryDto;
import com.ok.okhelper.pojo.po.*;
import com.ok.okhelper.service.DeliveryService;
import com.ok.okhelper.service.OtherService;
import com.ok.okhelper.shiro.JWTUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Author: zc
 * Date: 2018/5/2
 * Description:
 */
@Service
@Slf4j
public class DeliveryServiceImpl implements DeliveryService {
    @Autowired
    private OtherService otherService;

    @Autowired
    private DeliveryOrderMapper deliveryOrderMapper;

    @Autowired
    private DeliveryOrderDetailMapper deliveryOrderDetailMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private SalesOrderMapper salesOrderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String mailUsername;

    /**
     * @Author zc
     * @Date 2018/5/2 下午5:13
     * @Param [deliveryDto]
     * @Return 发货单
     * @Description:发货/出库
     */
    @Transactional
    public Long deliverGoods(DeliveryDto deliveryDto) {
        //校验发货单与子项
        otherService.checkDelivery(deliveryDto);

        //插入发货单
        DeliveryOrder deliveryOrder = new DeliveryOrder();
        deliveryOrder.setSalesOrderId(deliveryOrder.getSalesOrderId());
        deliveryOrder.setStockouter(JWTUtil.getUserId());

        deliveryOrderMapper.insertSelective(deliveryOrder);
        Long deliveryOrderId = deliveryOrder.getId();

        //向子项中注入发货单Id
        List<DeliveryOrderDetail> deliveryOrderDetails =
                deliveryDto.getDeliverItemDtos()
                        .stream()
                        .map(x -> {
                            DeliveryOrderDetail deliveryOrderDetail = new DeliveryOrderDetail();
                            BeanUtils.copyProperties(x, deliveryOrderDetail);
                            deliveryOrderDetail.setDeliveryOrderId(deliveryOrderId);
                            return deliveryOrderDetail;
                        }).collect(Collectors.toList());
        //插入子项
        deliveryOrderDetailMapper.insertList(deliveryOrderDetails);

        //修改真实库存
        deliveryOrderDetails.forEach(x -> {
            Stock stock = new Stock();
            stock.setOperator(JWTUtil.getUserId());
            stock.setProductId(x.getProductId());
            stock.setWarehouseId(x.getWarehouseId());
            stock.setProductDate(x.getProductDate());

            Stock dbstock = stockMapper.selectOne(stock);
            if (dbstock == null) {
                throw new NotFoundException("库存不存在，商品Id：" + x.getProductId() + "仓库Id：" + x.getWarehouseId() + "生产日期：" + x.getProductDate());
            }
            if (dbstock.getStockCount() < x.getDeliveryCount()) {
                throw new IllegalException("库存不足请重新出库，商品Id：" + x.getProductId() + "仓库Id：" + x.getWarehouseId() + "生产日期：" + x.getProductDate());
            }

            dbstock.setStockCount(dbstock.getStockCount() - x.getDeliveryCount());

            stockMapper.updateByPrimaryKeySelective(dbstock);
        });

        return deliveryOrderId;
    }


    /**
     * @Author zc
     * @Date 2018/5/2 下午6:04
     * @Param [customId, deliveryOrderId]
     * @Return void
     * @Description:给客户发邮件
     */
    @Async
    public void sendEmail(Long saleOrderId) {
        SalesOrder salesOrder = salesOrderMapper.selectByPrimaryKey(saleOrderId);

        Long customerId = salesOrder.getCustomerId();

        User user = userMapper.selectByPrimaryKey(customerId);

        if (user != null && StringUtils.isNoneBlank(user.getUserEmail())) {

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailUsername);
            message.setTo(user.getUserEmail());
            message.setSubject("标题：发货通知");
            message.setText(user.getUserName() + "你好，你的订单：" + salesOrder.getOrderNumber() + "已经发货了");
            try {
                mailSender.send(message);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }
}