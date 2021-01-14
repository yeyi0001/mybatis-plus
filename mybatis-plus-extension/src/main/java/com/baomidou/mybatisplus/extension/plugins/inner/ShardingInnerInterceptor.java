package com.baomidou.mybatisplus.extension.plugins.inner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.toolkit.ClassUtils;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.ExceptionUtils;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.parser.JsqlParserSupport;
import com.baomidou.mybatisplus.extension.plugins.handler.sharding.ShardingNode;
import com.baomidou.mybatisplus.extension.plugins.handler.sharding.ShardingNodeExtractor;
import com.baomidou.mybatisplus.extension.plugins.handler.sharding.ShardingProcessor;
import com.baomidou.mybatisplus.extension.plugins.handler.sharding.ShardingStrategy;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

/**
 * @author zengzhihong
 */
public class ShardingInnerInterceptor extends JsqlParserSupport implements InnerInterceptor {

    private final Map<String, ShardingStrategyProcessor> shardingMap;


    public ShardingInnerInterceptor(ShardingStrategy... shardingStrategies) {
        shardingMap =
                Arrays.stream(shardingStrategies).collect(Collectors.toMap(ShardingStrategy::getLogicTable, i -> new ShardingStrategyProcessor(i, ClassUtils.newInstance(i.getProcessor()))));
    }

    @AllArgsConstructor
    @Getter
    static class ShardingStrategyProcessor {

        private final ShardingStrategy strategy;
        private final ShardingProcessor processor;
    }

    @Override
    public void beforeGetBoundSql(StatementHandler sh) {
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        if (InterceptorIgnoreHelper.willIgnoreSharding(mpSh.mappedStatement().getId())) {
            return;
        }
        PluginUtils.MPBoundSql mpBs = mpSh.mPBoundSql();
        mpBs.sql(parserMulti(mpBs.sql(), mpSh));
    }

    @Override
    protected void processSelect(Select select, int index, String sql, Object obj) {
        process(select, (PluginUtils.MPStatementHandler) obj);
    }

    @Override
    protected void processInsert(Insert insert, int index, String sql, Object obj) {
        process(insert, (PluginUtils.MPStatementHandler) obj);
    }

    @Override
    protected void processUpdate(Update update, int index, String sql, Object obj) {
        process(update, (PluginUtils.MPStatementHandler) obj);
    }

    @Override
    protected void processDelete(Delete delete, int index, String sql, Object obj) {
        process(delete, (PluginUtils.MPStatementHandler) obj);
    }

    private void process(Statement statement, PluginUtils.MPStatementHandler mpSh) {
        final ShardingNodeExtractor shardingNodeExtractor = new ShardingNodeExtractor(statement);
        if (CollectionUtils.isEmpty(shardingNodeExtractor.getNodes())) {
            return;
        }
        final List<Object> parameterValues = handleParameter(mpSh);
        for (ShardingNode<Table, ShardingNode<String, Integer>> tableNode : shardingNodeExtractor.getNodes()) {
            final ShardingStrategyProcessor strategyProcessor = shardingMap.get(tableNode.getNode().getName());
            if (null == strategyProcessor) {
                continue;
            }
            Map<String, List<Object>> shardingValues = new HashMap<>(tableNode.getList().size());
            for (ShardingNode<String, Integer> columnNode : tableNode.getList()) {
                if (CollectionUtils.isEmpty(columnNode.getList()) || !strategyProcessor.getStrategy().containsColumn(columnNode.getNode())) {
                    continue;
                }
                shardingValues.put(columnNode.getNode(),
                        columnNode.getList().stream().map(i -> parameterValues.get(i - 1)).collect(Collectors.toList()));
            }
            if (CollectionUtils.isEmpty(shardingValues)) {
                throw ExceptionUtils.mpe("no fragment sharding column found");
            }
            tableNode.getNode().setName(strategyProcessor.getProcessor().doSharding(strategyProcessor.getStrategy(), shardingValues));
        }
    }

    private List<Object> handleParameter(PluginUtils.MPStatementHandler mpSh) {
        List<Object> values = new ArrayList<>();
        final Object parameterObject = mpSh.boundSql().getParameterObject();
        List<ParameterMapping> parameterMappings = mpSh.boundSql().getParameterMappings();
        if (parameterMappings != null) {
            for (ParameterMapping parameterMapping : parameterMappings) {
                if (parameterMapping.getMode() != ParameterMode.OUT) {
                    Object value;
                    String propertyName = parameterMapping.getProperty();
                    if (mpSh.boundSql().hasAdditionalParameter(propertyName)) { // issue #448 ask first for
                        // additional params
                        value = mpSh.boundSql().getAdditionalParameter(propertyName);
                    } else if (parameterObject == null) {
                        value = null;
                    } else if (mpSh.configuration().getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass())) {
                        value = parameterObject;
                    } else {
                        MetaObject metaObject = mpSh.configuration().newMetaObject(parameterObject);
                        value = metaObject.getValue(propertyName);
                    }
                    values.add(value);
                }
            }
        }
        return values;
    }

}
