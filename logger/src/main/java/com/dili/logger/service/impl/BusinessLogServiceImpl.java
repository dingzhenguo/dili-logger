package com.dili.logger.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.dili.logger.config.ESConfig;
import com.dili.logger.domain.BusinessLog;
import com.dili.logger.mapper.BusinessLogRepository;
import com.dili.logger.sdk.domain.input.BusinessLogQueryInput;
import com.dili.logger.service.BusinessLogService;
import com.dili.logger.service.remote.DataDictionaryRpcService;
import com.dili.ss.domain.BaseOutput;
import com.dili.ss.domain.PageOutput;
import com.dili.ss.sid.util.IdUtils;
import com.dili.uap.sdk.domain.DataDictionaryValue;
import com.google.common.collect.Lists;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.ScrolledPage;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <B>Description</B>
 * <B>Copyright:本软件源代码版权归农丰时代所有,未经许可不得任意复制与传播.</B>
 * <B>农丰时代科技有限公司</B>
 *
 * @author yuehongbo
 * @date 2020/2/10 18:12
 */
@Service
public class BusinessLogServiceImpl implements BusinessLogService<BusinessLog> {

    @Autowired
    private BusinessLogRepository businessLogRepository;

    @Autowired
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Autowired
    private DataDictionaryRpcService dataDictionaryRpcService;


    @Override
    public PageOutput<List<BusinessLog>> searchPage(BusinessLogQueryInput condition) {
        // 创建对象
        NativeSearchQueryBuilder searchQuery = new NativeSearchQueryBuilder();
        QueryBuilder queryBuilder = produceQuery(condition);
        searchQuery.withQuery(queryBuilder);
        searchQuery.withSort(SortBuilders.fieldSort("createTime").order(SortOrder.DESC));
        //是否需要循环遍历pageNum次轮询
        Boolean isGetAll = false;
        //当前页码
        Integer pageNum = 1;
        /**
         * 由于es 默认最大允许size有限，所以需要针对size进行数据转换操作
         */
        if (Objects.nonNull(condition.getRows()) && condition.getRows() > 0) {
            //传入的页码是否为空
            boolean b = Objects.isNull(condition.getPage());
            if (b) {
                if (condition.getRows().compareTo(Integer.valueOf(ESConfig.getMaxSize())) > 0) {
                    pageNum = (int) Math.ceil((double) condition.getRows() / (double) ESConfig.getMaxSize());
                    condition.setRows(ESConfig.getMaxSize());
                }
                isGetAll = true;
            } else {
                //ES 默认size 是10000，超过配置，则会直接报错
                if (condition.getRows().compareTo(Integer.valueOf(ESConfig.getMaxSize())) > 0) {
                    return PageOutput.failure("分页-每页显示条数过多，暂且不支持");
                }
                pageNum = condition.getPage();
            }
            searchQuery.withPageable(PageRequest.of(pageNum - 1, condition.getRows()));
        } else {
            //如果未传入rows，因为es默认查询10条，所有，则认为查询所有(es允许的单页最大值)
            isGetAll = true;
            searchQuery.withPageable(PageRequest.of(pageNum - 1, ESConfig.getMaxSize()));
        }
        ScrolledPage<BusinessLog> pageInfo = elasticsearchRestTemplate.startScroll(5000, searchQuery.build(), BusinessLog.class);
        if (0 == pageInfo.getTotalElements()) {
            return PageOutput.success();
        }
        PageOutput output = PageOutput.success();
        output.setPages(pageInfo.getTotalPages());
        output.setPageNum(pageNum).setTotal(Long.valueOf(pageInfo.getTotalElements()).intValue());
        List<BusinessLog> allData = Lists.newArrayList();
        allData.addAll(pageInfo.getContent());
        for (int i = 0; i < pageNum - 1; i++) {
            pageInfo = elasticsearchRestTemplate.continueScroll(pageInfo.getScrollId(), 5000, BusinessLog.class);
            if (isGetAll) {
                allData.addAll(pageInfo.getContent());
            }
        }
        //释放es服务器资源
        elasticsearchRestTemplate.clearScroll(pageInfo.getScrollId());
        output.setData(isGetAll ? allData : pageInfo.getContent());
        return output;
    }

    @Override
    public BaseOutput<List<BusinessLog>> list(BusinessLogQueryInput condition) {
        return BaseOutput.success().setData(this.searchPage(condition).getData());
    }

    @Override
    public void save(BusinessLog log) {
        //由日志系统生成统一的ID，忽略客户传入的ID
        log.setId(IdUtils.nextId());
        if (Objects.isNull(log.getCreateTime())) {
            log.setCreateTime(LocalDateTime.now());
        }
        if (StrUtil.isBlank(log.getOperationTypeText())) {
            log.setOperationTypeText(dataDictionaryRpcService.getByDdCodeAndCode("operation_type", log.getOperationType()).map(DataDictionaryValue::getName).orElse(""));
        }
        businessLogRepository.save(log);
    }

    @Override
    public void batchSave(List<BusinessLog> logList) {
        if (CollectionUtil.isNotEmpty(logList)) {
            Map<String, DataDictionaryValue> operationTypeMap = dataDictionaryRpcService.getOperationTypeMap();
            logList.forEach(l -> {
                //由日志系统生成统一的ID，忽略客户传入的ID
                l.setId(IdUtils.nextId());
                if (Objects.isNull(l.getCreateTime())) {
                    l.setCreateTime(LocalDateTime.now());
                }
                if (StrUtil.isBlank(l.getOperationTypeText())) {
                    l.setOperationTypeText(operationTypeMap.containsKey(l.getOperationType()) ? operationTypeMap.get(l.getOperationType()).getName() : "");
                }
            });
            businessLogRepository.saveAll(logList);
        }
    }

    @Override
    public void deleteAll() {
        businessLogRepository.deleteAll();
    }

    /**
     * 构造查询条件
     *
     * @param condition 根据传入参数，构造查询条件
     * @return
     */
    private QueryBuilder produceQuery(BusinessLogQueryInput condition) {
        // 在queryBuilder对象中自定义查询
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        if (Objects.nonNull(condition)) {
            if (Objects.nonNull(condition.getSystemCode())) {
                queryBuilder.must(QueryBuilders.termQuery("systemCode", condition.getSystemCode()));
            }
            if (Objects.nonNull(condition.getOperatorId())) {
                queryBuilder.must(QueryBuilders.termQuery("operatorId", condition.getOperatorId()));
            }
            if (Objects.nonNull(condition.getBusinessId())) {
                queryBuilder.must(QueryBuilders.termQuery("businessId", condition.getBusinessId()));
            }
            if (StrUtil.isNotBlank(condition.getBusinessType())) {
                queryBuilder.must(QueryBuilders.termQuery("businessType", condition.getBusinessType()));
            }
            if (Objects.nonNull(condition.getCreateTimeStart())) {
                queryBuilder.filter(QueryBuilders.rangeQuery("createTime").gte(condition.getCreateTimeStart()));
            }
            if (Objects.nonNull(condition.getCreateTimeEnd())) {
                queryBuilder.filter(QueryBuilders.rangeQuery("createTime").lte(condition.getCreateTimeEnd()));
            }
            if (StrUtil.isNotBlank(condition.getBusinessCode())){
                queryBuilder.must(QueryBuilders.termQuery("businessCode", condition.getBusinessCode()));
            }
            if (StrUtil.isNotBlank(condition.getOperationType())){
                queryBuilder.must(QueryBuilders.termQuery("operationType", condition.getOperationType()));
            }
            if (Objects.nonNull(condition.getMarketId())) {
                queryBuilder.must(QueryBuilders.termQuery("marketId", condition.getMarketId()));
            }
            if (CollectionUtil.isNotEmpty(condition.getMarketIdSet())) {
                queryBuilder.filter(QueryBuilders.termsQuery("marketId", condition.getMarketIdSet()));
            }
            if (CollectionUtil.isNotEmpty(condition.getOperationTypeSet())) {
                queryBuilder.filter(QueryBuilders.termsQuery("operationType", condition.getOperationTypeSet()));
            }
        }
        return queryBuilder;
    }
}
