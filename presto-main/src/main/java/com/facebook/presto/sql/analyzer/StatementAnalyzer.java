/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.analyzer;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataUtil;
import com.facebook.presto.metadata.QualifiedTableName;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.AllColumns;
import com.facebook.presto.sql.tree.Approximate;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.CoalesceExpression;
import com.facebook.presto.sql.tree.CreateTable;
import com.facebook.presto.sql.tree.CreateView;
import com.facebook.presto.sql.tree.DefaultTraversalVisitor;
import com.facebook.presto.sql.tree.Explain;
import com.facebook.presto.sql.tree.ExplainFormat;
import com.facebook.presto.sql.tree.ExplainOption;
import com.facebook.presto.sql.tree.ExplainType;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.IfExpression;
import com.facebook.presto.sql.tree.Insert;
import com.facebook.presto.sql.tree.LikePredicate;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.Row;
import com.facebook.presto.sql.tree.SelectItem;
import com.facebook.presto.sql.tree.ShowCatalogs;
import com.facebook.presto.sql.tree.ShowColumns;
import com.facebook.presto.sql.tree.ShowFunctions;
import com.facebook.presto.sql.tree.ShowPartitions;
import com.facebook.presto.sql.tree.ShowSchemas;
import com.facebook.presto.sql.tree.ShowSession;
import com.facebook.presto.sql.tree.ShowTables;
import com.facebook.presto.sql.tree.SingleColumn;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.sql.tree.StringLiteral;
import com.facebook.presto.sql.tree.Use;
import com.facebook.presto.sql.tree.Values;
import com.facebook.presto.sql.tree.With;
import com.facebook.presto.sql.tree.WithQuery;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_COLUMNS;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_INTERNAL_FUNCTIONS;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_INTERNAL_PARTITIONS;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_SCHEMATA;
import static com.facebook.presto.connector.informationSchema.InformationSchemaMetadata.TABLE_TABLES;
import static com.facebook.presto.connector.system.CatalogSystemTable.CATALOG_TABLE_NAME;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.sql.QueryUtil.aliased;
import static com.facebook.presto.sql.QueryUtil.aliasedName;
import static com.facebook.presto.sql.QueryUtil.ascending;
import static com.facebook.presto.sql.QueryUtil.caseWhen;
import static com.facebook.presto.sql.QueryUtil.equal;
import static com.facebook.presto.sql.QueryUtil.functionCall;
import static com.facebook.presto.sql.QueryUtil.logicalAnd;
import static com.facebook.presto.sql.QueryUtil.nameReference;
import static com.facebook.presto.sql.QueryUtil.row;
import static com.facebook.presto.sql.QueryUtil.selectAll;
import static com.facebook.presto.sql.QueryUtil.selectList;
import static com.facebook.presto.sql.QueryUtil.subquery;
import static com.facebook.presto.sql.QueryUtil.table;
import static com.facebook.presto.sql.QueryUtil.unaliasedName;
import static com.facebook.presto.sql.QueryUtil.values;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.COLUMN_NAME_NOT_SPECIFIED;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.DUPLICATE_COLUMN_NAME;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.DUPLICATE_RELATION;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.INVALID_ORDINAL;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.INVALID_SCHEMA_NAME;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MISMATCHED_SET_COLUMN_TYPES;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MISSING_TABLE;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.TABLE_ALREADY_EXISTS;
import static com.facebook.presto.sql.tree.ExplainFormat.Type.TEXT;
import static com.facebook.presto.sql.tree.ExplainType.Type.LOGICAL;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.elementsEqual;
import static com.google.common.collect.Iterables.transform;

class StatementAnalyzer
        extends DefaultTraversalVisitor<TupleDescriptor, AnalysisContext>
{
    private final Analysis analysis;
    private final Metadata metadata;
    private final Session session;
    private final Optional<QueryExplainer> queryExplainer;
    private final boolean experimentalSyntaxEnabled;
    private final SqlParser sqlParser;

    public StatementAnalyzer(
            Analysis analysis,
            Metadata metadata,
            SqlParser sqlParser,
            Session session,
            boolean experimentalSyntaxEnabled,
            Optional<QueryExplainer> queryExplainer)
    {
        this.analysis = checkNotNull(analysis, "analysis is null");
        this.metadata = checkNotNull(metadata, "metadata is null");
        this.sqlParser = checkNotNull(sqlParser, "sqlParser is null");
        this.session = checkNotNull(session, "session is null");
        this.experimentalSyntaxEnabled = experimentalSyntaxEnabled;
        this.queryExplainer = checkNotNull(queryExplainer, "queryExplainer is null");
    }

    @Override
    protected TupleDescriptor visitShowTables(ShowTables showTables, AnalysisContext context)
    {
        String catalogName = session.getCatalog();
        String schemaName = session.getSchema();

        QualifiedName schema = showTables.getSchema();
        if (schema != null) {
            List<String> parts = schema.getParts();
            if (parts.size() > 2) {
                throw new SemanticException(INVALID_SCHEMA_NAME, showTables, "too many parts in schema name: %s", schema);
            }
            if (parts.size() == 2) {
                catalogName = parts.get(0);
            }
            schemaName = schema.getSuffix();
        }

        // TODO: throw SemanticException if schema does not exist

        Expression predicate = equal(nameReference("table_schema"), new StringLiteral(schemaName));

        String likePattern = showTables.getLikePattern();
        if (likePattern != null) {
            Expression likePredicate = new LikePredicate(nameReference("table_name"), new StringLiteral(likePattern), null);
            predicate = logicalAnd(predicate, likePredicate);
        }

        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectList(aliasedName("table_name", "Table")),
                        table(QualifiedName.of(catalogName, TABLE_TABLES.getSchemaName(), TABLE_TABLES.getTableName())),
                        Optional.of(predicate),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.of(ascending("table_name")),
                        Optional.empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitShowSchemas(ShowSchemas node, AnalysisContext context)
    {
        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectList(aliasedName("schema_name", "Schema")),
                        table(QualifiedName.of(node.getCatalog().orElse(session.getCatalog()), TABLE_SCHEMATA.getSchemaName(), TABLE_SCHEMATA.getTableName())),
                        Optional.empty(),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.of(ascending("schema_name")),
                        Optional.empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitShowCatalogs(ShowCatalogs node, AnalysisContext context)
    {
        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectList(aliasedName("catalog_name", "Catalog")),
                        table(QualifiedName.of(session.getCatalog(), CATALOG_TABLE_NAME.getSchemaName(), CATALOG_TABLE_NAME.getTableName())),
                        Optional.empty(),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.of(ascending("catalog_name")),
                        Optional.empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitShowColumns(ShowColumns showColumns, AnalysisContext context)
    {
        QualifiedTableName tableName = MetadataUtil.createQualifiedTableName(session, showColumns.getTable());

        if (!metadata.getView(session, tableName).isPresent() &&
                !metadata.getTableHandle(session, tableName).isPresent()) {
            throw new SemanticException(MISSING_TABLE, showColumns, "Table '%s' does not exist", tableName);
        }

        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectList(
                                aliasedName("column_name", "Column"),
                                aliasedName("data_type", "Type"),
                                aliasedYesNoToBoolean("is_nullable", "Null"),
                                aliasedYesNoToBoolean("is_partition_key", "Partition Key"),
                                aliasedNullToEmpty("comment", "Comment")),
                        table(QualifiedName.of(tableName.getCatalogName(), TABLE_COLUMNS.getSchemaName(), TABLE_COLUMNS.getTableName())),
                        Optional.of(logicalAnd(
                                equal(nameReference("table_schema"), new StringLiteral(tableName.getSchemaName())),
                                equal(nameReference("table_name"), new StringLiteral(tableName.getTableName())))),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.of(ascending("ordinal_position")),
                        Optional.empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitUse(Use node, AnalysisContext context)
    {
        throw new SemanticException(NOT_SUPPORTED, node, "USE statement is not supported");
    }

    private static SelectItem aliasedYesNoToBoolean(String column, String alias)
    {
        Expression expression = new IfExpression(
                equal(nameReference(column), new StringLiteral("YES")),
                BooleanLiteral.TRUE_LITERAL,
                BooleanLiteral.FALSE_LITERAL);
        return new SingleColumn(expression, alias);
    }

    private static SelectItem aliasedNullToEmpty(String column, String alias)
    {
        return new SingleColumn(new CoalesceExpression(nameReference(column), new StringLiteral("")), alias);
    }

    @Override
    protected TupleDescriptor visitShowPartitions(ShowPartitions showPartitions, AnalysisContext context)
    {
        QualifiedTableName table = MetadataUtil.createQualifiedTableName(session, showPartitions.getTable());
        Optional<TableHandle> tableHandle = metadata.getTableHandle(session, table);
        if (!tableHandle.isPresent()) {
            throw new SemanticException(MISSING_TABLE, showPartitions, "Table '%s' does not exist", table);
        }

            /*
                Generate a dynamic pivot to output one column per partition key.
                For example, a table with two partition keys (ds, cluster_name)
                would generate the following query:

                SELECT
                  partition_number
                , max(CASE WHEN partition_key = 'ds' THEN partition_value END) ds
                , max(CASE WHEN partition_key = 'cluster_name' THEN partition_value END) cluster_name
                FROM ...
                GROUP BY partition_number

                The values are also cast to the type of the partition column.
                The query is then wrapped to allow custom filtering and ordering.
            */

        ImmutableList.Builder<SelectItem> selectList = ImmutableList.builder();
        ImmutableList.Builder<SelectItem> wrappedList = ImmutableList.builder();
        selectList.add(unaliasedName("partition_number"));
        for (ColumnMetadata column : metadata.getTableMetadata(tableHandle.get()).getColumns()) {
            if (!column.isPartitionKey()) {
                continue;
            }
            Expression key = equal(nameReference("partition_key"), new StringLiteral(column.getName()));
            Expression value = caseWhen(key, nameReference("partition_value"));
            value = new Cast(value, column.getType().getTypeSignature().toString());
            Expression function = functionCall("max", value);
            selectList.add(new SingleColumn(function, column.getName()));
            wrappedList.add(unaliasedName(column.getName()));
        }

        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectAll(selectList.build()),
                        table(QualifiedName.of(table.getCatalogName(), TABLE_INTERNAL_PARTITIONS.getSchemaName(), TABLE_INTERNAL_PARTITIONS.getTableName())),
                        Optional.of(logicalAnd(
                                equal(nameReference("table_schema"), new StringLiteral(table.getSchemaName())),
                                equal(nameReference("table_name"), new StringLiteral(table.getTableName())))),
                        ImmutableList.of(nameReference("partition_number")),
                        Optional.empty(),
                        ImmutableList.<SortItem>of(),
                        Optional.empty()),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectAll(wrappedList.build()),
                        subquery(query),
                        showPartitions.getWhere(),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.<SortItem>builder()
                                .addAll(showPartitions.getOrderBy())
                                .add(ascending("partition_number"))
                                .build(),
                        showPartitions.getLimit()),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitShowFunctions(ShowFunctions node, AnalysisContext context)
    {
        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectList(
                                aliasedName("function_name", "Function"),
                                aliasedName("return_type", "Return Type"),
                                aliasedName("argument_types", "Argument Types"),
                                aliasedName("function_type", "Function Type"),
                                aliasedName("deterministic", "Deterministic"),
                                aliasedName("description", "Description")),
                        table(QualifiedName.of(TABLE_INTERNAL_FUNCTIONS.getSchemaName(), TABLE_INTERNAL_FUNCTIONS.getTableName())),
                        Optional.empty(),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.of(
                                ascending("function_name"),
                                ascending("return_type"),
                                ascending("argument_types"),
                                ascending("function_type")),
                        Optional.empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitShowSession(ShowSession node, AnalysisContext context)
    {
        ImmutableList.Builder<Row> rows = ImmutableList.builder();
        for (Entry<String, String> property : new TreeMap<>(session.getSystemProperties()).entrySet()) {
            rows.add(new Row(ImmutableList.<Expression>of(
                    new StringLiteral(property.getKey()),
                    new StringLiteral(property.getValue()),
                    new BooleanLiteral("true"))));
        }
        for (Entry<String, Map<String, String>> entry : new TreeMap<>(session.getCatalogProperties()).entrySet()) {
            String catalog = entry.getKey();
            for (Entry<String, String> property : new TreeMap<>(entry.getValue()).entrySet()) {
                rows.add(new Row(ImmutableList.<Expression>of(
                        new StringLiteral(catalog + "." + property.getKey()),
                        new StringLiteral(property.getValue()),
                        new BooleanLiteral("true"))));
            }
        }

        // add bogus row so we can support empty sessions
        rows.add(new Row(ImmutableList.<Expression>of(new StringLiteral(""), new StringLiteral(""), new BooleanLiteral("false"))));

        Query query = new Query(
                Optional.<With>empty(),
                new QuerySpecification(
                        selectList(
                                aliasedName("name", "Name"),
                                aliasedName("value", "Value")),
                        Optional.of(aliased(
                                new Values(rows.build()),
                                "session",
                                ImmutableList.of("name", "value", "include"))),
                        Optional.<Expression>empty(),
                        ImmutableList.<Expression>of(),
                        Optional.<Expression>of(nameReference("include")),
                        ImmutableList.<SortItem>of(),
                        Optional.<String>empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.<String>empty(),
                Optional.<Approximate>empty());

        return process(query, context);
    }

    @Override
    protected TupleDescriptor visitInsert(Insert insert, AnalysisContext context)
    {
        // analyze the query that creates the data
        TupleDescriptor descriptor = process(insert.getQuery(), context);

        // verify the insert destination columns match the query
        QualifiedTableName targetTable = MetadataUtil.createQualifiedTableName(session, insert.getTarget());
        Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, targetTable);
        if (!targetTableHandle.isPresent()) {
            throw new SemanticException(MISSING_TABLE, insert, "Table '%s' does not exist", targetTable);
        }
        analysis.setInsertTarget(targetTableHandle.get());

        List<ColumnMetadata> columns = metadata.getTableMetadata(targetTableHandle.get()).getColumns();
        Iterable<Type> tableTypes = transform(columns, ColumnMetadata::getType);

        Iterable<Type> queryTypes = transform(descriptor.getVisibleFields(), Field::getType);

        if (!elementsEqual(tableTypes, queryTypes)) {
            throw new SemanticException(MISMATCHED_SET_COLUMN_TYPES, insert, "Insert query has mismatched column types: " +
                    "Table: (" + Joiner.on(", ").join(tableTypes) + "), " +
                    "Query: (" + Joiner.on(", ").join(queryTypes) + ")");
        }

        return new TupleDescriptor(Field.newUnqualified("rows", BIGINT));
    }

    @Override
    protected TupleDescriptor visitCreateTable(CreateTable node, AnalysisContext context)
    {
        // turn this into a query that has a new table writer node on top.
        QualifiedTableName targetTable = MetadataUtil.createQualifiedTableName(session, node.getName());
        analysis.setCreateTableDestination(targetTable);

        Optional<TableHandle> targetTableHandle = metadata.getTableHandle(session, targetTable);
        if (targetTableHandle.isPresent()) {
            throw new SemanticException(TABLE_ALREADY_EXISTS, node, "Destination table '%s' already exists", targetTable);
        }

        // analyze the query that creates the table
        TupleDescriptor descriptor = process(node.getQuery(), context);

        validateColumnNames(node, descriptor);

        return new TupleDescriptor(Field.newUnqualified("rows", BIGINT));
    }

    @Override
    protected TupleDescriptor visitCreateView(CreateView node, AnalysisContext context)
    {
        // analyze the query that creates the view
        TupleDescriptor descriptor = process(node.getQuery(), context);

        validateColumnNames(node, descriptor);

        return descriptor;
    }

    private static void validateColumnNames(Statement node, TupleDescriptor descriptor)
    {
        // verify that all column names are specified and unique
        // TODO: collect errors and return them all at once
        Set<String> names = new HashSet<>();
        for (Field field : descriptor.getVisibleFields()) {
            Optional<String> fieldName = field.getName();
            if (!fieldName.isPresent()) {
                throw new SemanticException(COLUMN_NAME_NOT_SPECIFIED, node, "Column name not specified at position %s", descriptor.indexOf(field) + 1);
            }
            if (!names.add(fieldName.get())) {
                throw new SemanticException(DUPLICATE_COLUMN_NAME, node, "Column name '%s' specified more than once", fieldName.get());
            }
        }
    }

    @Override
    protected TupleDescriptor visitExplain(Explain node, AnalysisContext context)
            throws SemanticException
    {
        checkState(queryExplainer.isPresent(), "query explainer not available");
        ExplainType.Type planType = LOGICAL;
        ExplainFormat.Type planFormat = TEXT;
        List<ExplainOption> options = node.getOptions();

        for (ExplainOption option : options) {
            if (option instanceof ExplainType) {
                planType = ((ExplainType) option).getType();
                break;
            }
        }

        for (ExplainOption option : options) {
            if (option instanceof ExplainFormat) {
                planFormat = ((ExplainFormat) option).getType();
                break;
            }
        }

        String queryPlan = getQueryPlan(node, planType, planFormat);

        Query query = new Query(
                Optional.empty(),
                new QuerySpecification(
                        selectList(new AllColumns()),
                        Optional.of(aliased(
                                values(row(new StringLiteral((queryPlan)))),
                                "plan",
                                ImmutableList.of("Query Plan")
                        )),
                        Optional.empty(),
                        ImmutableList.<Expression>of(),
                        Optional.empty(),
                        ImmutableList.<SortItem>of(),
                        Optional.empty()
                ),
                ImmutableList.<SortItem>of(),
                Optional.empty(),
                Optional.empty());

        return process(query, context);
    }

    private String getQueryPlan(Explain node, ExplainType.Type planType, ExplainFormat.Type planFormat)
            throws IllegalArgumentException
    {
        switch (planFormat) {
            case GRAPHVIZ:
                return queryExplainer.get().getGraphvizPlan(node.getStatement(), planType);
            case TEXT:
                return queryExplainer.get().getPlan(node.getStatement(), planType);
            case JSON:
                // ignore planType if planFormat is JSON
                return queryExplainer.get().getJsonPlan(node.getStatement());
        }
        throw new IllegalArgumentException("Invalid Explain Format: " + planFormat.toString());
    }

    @Override
    protected TupleDescriptor visitQuery(Query node, AnalysisContext parentContext)
    {
        AnalysisContext context = new AnalysisContext(parentContext);

        if (node.getApproximate().isPresent()) {
            if (!experimentalSyntaxEnabled) {
                throw new SemanticException(NOT_SUPPORTED, node, "approximate queries are not enabled");
            }
            context.setApproximate(true);
        }

        analyzeWith(node, context);

        TupleAnalyzer analyzer = new TupleAnalyzer(analysis, session, metadata, sqlParser, experimentalSyntaxEnabled);
        TupleDescriptor descriptor = analyzer.process(node.getQueryBody(), context);
        analyzeOrderBy(node, descriptor, context);

        // Input fields == Output fields
        analysis.setOutputDescriptor(node, descriptor);
        analysis.setOutputExpressions(node, descriptorToFields(descriptor));
        analysis.setQuery(node);

        return descriptor;
    }

    private List<FieldOrExpression> descriptorToFields(TupleDescriptor tupleDescriptor)
    {
        ImmutableList.Builder<FieldOrExpression> builder = ImmutableList.builder();
        for (int fieldIndex = 0; fieldIndex < tupleDescriptor.getAllFieldCount(); fieldIndex++) {
            builder.add(new FieldOrExpression(fieldIndex));
        }
        return builder.build();
    }

    private void analyzeWith(Query node, AnalysisContext context)
    {
        // analyze WITH clause
        if (!node.getWith().isPresent()) {
            return;
        }

        With with = node.getWith().get();
        if (with.isRecursive()) {
            throw new SemanticException(NOT_SUPPORTED, with, "Recursive WITH queries are not supported");
        }

        for (WithQuery withQuery : with.getQueries()) {
            if (withQuery.getColumnNames() != null && !withQuery.getColumnNames().isEmpty()) {
                throw new SemanticException(NOT_SUPPORTED, withQuery, "Column alias not supported in WITH queries");
            }

            Query query = withQuery.getQuery();
            process(query, context);

            String name = withQuery.getName();
            if (context.isNamedQueryDeclared(name)) {
                throw new SemanticException(DUPLICATE_RELATION, withQuery, "WITH query name '%s' specified more than once", name);
            }

            context.addNamedQuery(name, query);
        }
    }

    private void analyzeOrderBy(Query node, TupleDescriptor tupleDescriptor, AnalysisContext context)
    {
        List<SortItem> items = node.getOrderBy();

        ImmutableList.Builder<FieldOrExpression> orderByFieldsBuilder = ImmutableList.builder();

        if (!items.isEmpty()) {
            for (SortItem item : items) {
                Expression expression = item.getSortKey();

                FieldOrExpression orderByField;
                if (expression instanceof LongLiteral) {
                    // this is an ordinal in the output tuple

                    long ordinal = ((LongLiteral) expression).getValue();
                    if (ordinal < 1 || ordinal > tupleDescriptor.getVisibleFieldCount()) {
                        throw new SemanticException(INVALID_ORDINAL, expression, "ORDER BY position %s is not in select list", ordinal);
                    }

                    orderByField = new FieldOrExpression((int) (ordinal - 1));
                }
                else {
                    // otherwise, just use the expression as is
                    orderByField = new FieldOrExpression(expression);
                    ExpressionAnalysis expressionAnalysis = ExpressionAnalyzer.analyzeExpression(session,
                            metadata,
                            sqlParser,
                            tupleDescriptor,
                            analysis,
                            experimentalSyntaxEnabled,
                            context,
                            orderByField.getExpression());
                    analysis.addInPredicates(node, expressionAnalysis.getSubqueryInPredicates());
                }

                orderByFieldsBuilder.add(orderByField);
            }
        }

        analysis.setOrderByExpressions(node, orderByFieldsBuilder.build());
    }
}
