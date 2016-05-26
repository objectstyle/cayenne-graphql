package org.objectstyle.graphql.cayenne.orm;

import graphql.Scalars;
import graphql.schema.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.query.Select;
import org.apache.cayenne.query.SelectQuery;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class SchemaBuilder {
    private ConcurrentMap<Class<?>, GraphQLScalarType> typeCache;
    private GraphQLSchema graphQLSchema;
    private ObjectContext objectContext;
    private Class<? extends DefaultDataFetcher> dataFetcher = DefaultDataFetcher.class;

    private EntityBuilder entityBuilder;

    private Map<String, Select<?>> queries = new HashMap<>();

    private SchemaBuilder(EntityBuilder entityBuilder) {
        this.entityBuilder = entityBuilder;
        this.objectContext = entityBuilder.getObjectContext();
    }

    private SchemaBuilder initialize() {
        this.typeCache = new ConcurrentHashMap<>();

        typeCache.put(Boolean.class, Scalars.GraphQLBoolean);

        typeCache.put(String.class, Scalars.GraphQLString);

        typeCache.put(Integer.class, Scalars.GraphQLInt);
        typeCache.put(Integer.TYPE, Scalars.GraphQLInt);
        typeCache.put(Short.class, Scalars.GraphQLInt);
        typeCache.put(Short.TYPE, Scalars.GraphQLInt);
        typeCache.put(Byte.class, Scalars.GraphQLInt);
        typeCache.put(Byte.TYPE, Scalars.GraphQLInt);

        typeCache.put(Long.class, Scalars.GraphQLLong);
        typeCache.put(Long.TYPE, Scalars.GraphQLLong);
        typeCache.put(BigInteger.class, Scalars.GraphQLLong);

        typeCache.put(Float.class, Scalars.GraphQLFloat);
        typeCache.put(Float.TYPE, Scalars.GraphQLFloat);
        typeCache.put(Double.class, Scalars.GraphQLFloat);
        typeCache.put(Double.TYPE, Scalars.GraphQLFloat);
        typeCache.put(BigDecimal.class, Scalars.GraphQLFloat);

        Set entityTypes = entityTypes();
        GraphQLObjectType rootQueryType = queryType(entityTypes);

        graphQLSchema = GraphQLSchema.newSchema().query(rootQueryType).build(entityTypes);

        return this;
    }

    private DataFetcher getDataFetcher() {
        DataFetcher df = null;

        try {
            df = dataFetcher.getConstructor(ObjectContext.class).newInstance(objectContext);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return df;
    }

    private GraphQLObjectType queryType(Set<GraphQLObjectType> entityTypes) {
        GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject().name("root");

        // naive... root type should be a user-visible builder
        entityTypes.forEach(et -> {
            List<GraphQLArgument> argList = new ArrayList<>();

            et.getFieldDefinitions().forEach(fd -> {
                if (fd.getType() instanceof GraphQLScalarType) {
                    argList.add(GraphQLArgument
                            .newArgument()
                            .name(fd.getName())
                            .type((GraphQLInputType) fd.getType())
                            .build());
                }
            });

            argList.addAll(createDefaultFilters());

            // ... create select operations for all entities
            GraphQLFieldDefinition f = GraphQLFieldDefinition.newFieldDefinition()
                    .name("all" + et.getName() + "s")
                    .type(new GraphQLList(et))
                    .argument(argList)
                    .dataFetcher(getDataFetcher())
                    .build();

            typeBuilder.field(f);

            // ... create search by field operations for all entities
            f = GraphQLFieldDefinition.newFieldDefinition()
                    .name(et.getName())
                    .type(new GraphQLList(et))
                    .argument(argList)
                    .dataFetcher(getDataFetcher())
                    .build();

            typeBuilder.field(f);
        });

        queries.forEach((k, v) -> {
            GraphQLObjectType ot = null;
            String entityName = null;

            if (v instanceof SelectQuery) {
                entityName = ((SelectQuery<?>) v).getRoot().toString();
            }

            for (GraphQLObjectType o : entityTypes) {
                if (o.getName().equals(entityName)) {
                    ot = o;
                    break;
                }
            }

            if (ot != null) {
                List<GraphQLArgument> argList = new ArrayList<>();

                ot.getFieldDefinitions().forEach(fd -> {
                    if (fd.getType() instanceof GraphQLScalarType) {
                        argList.add(GraphQLArgument
                                .newArgument()
                                .name(fd.getName())
                                .type((GraphQLInputType) fd.getType())
                                .build());
                    }
                });

                argList.addAll(createDefaultFilters());

                GraphQLFieldDefinition f = GraphQLFieldDefinition.newFieldDefinition()
                        .name(k)
                        .type(new GraphQLList(ot))
                        .argument(argList)
                        .dataFetcher(new CustomQueryDataFetcher(objectContext, v))
                        .build();

                typeBuilder.field(f);
            }
        });

        return typeBuilder.build();
    }

    private Set<GraphQLObjectType> entityTypes() {

        Set<GraphQLObjectType> types = new HashSet<>();

        for (Entity oe : entityBuilder.getEntities()) {
            GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject().name(oe.getObjEntity().getName());

            // add attributes
            oe.getAttributes().forEach(oa -> {
                GraphQLFieldDefinition f = GraphQLFieldDefinition.newFieldDefinition()
                        .name(oa.getName())
                        .type(mapType(oa.getJavaClass()))
                        .build();

                typeBuilder.field(f);
            });

            // add relationships
            for (ObjRelationship or : oe.getRelationships()) {
                List<GraphQLArgument> argList = new ArrayList<>();

                Entity e = entityBuilder.getEntityByName(or.getTargetEntityName());

                if (e != null) {
                    e.getAttributes().forEach(tea -> argList.add(GraphQLArgument
                            .newArgument()
                            .name(tea.getName())
                            .type(mapType(tea.getJavaClass()))
                            .build()));

                    argList.addAll(createDefaultFilters());
                }

                GraphQLFieldDefinition f = GraphQLFieldDefinition.newFieldDefinition()
                        .name(or.getName())
                        .argument(argList.stream().distinct().collect(Collectors.toList()))
                        .type(or.isToMany() ? new GraphQLList(new GraphQLTypeReference(or.getTargetEntityName())) : new GraphQLTypeReference(or.getTargetEntityName()))
                        .dataFetcher(getDataFetcher())
                        .build();

                typeBuilder.field(f);
            }

            types.add(typeBuilder.build());
        }

        return types;
    }

    private List<GraphQLArgument> createDefaultFilters() {
        List<GraphQLArgument> argList = new ArrayList<>();

        new DefaultFilters().getFilters().forEach((k, v) -> {
            if (k != FilterType.UNDEFINED) {
                argList.add(GraphQLArgument
                        .newArgument()
                        .name(v)
                        .type(new GraphQLList(Scalars.GraphQLString))
                        .build());
            }
        });

        return argList;
    }

    private GraphQLScalarType mapType(Class<?> javaType) {
        return typeCache.computeIfAbsent(javaType, jt -> Scalars.GraphQLString);
    }

    public static Builder newSchemaBuilder(EntityBuilder entityBuilder) {
        return new Builder(entityBuilder);
    }

    private GraphQLSchema getGraphQLSchema() {
        return graphQLSchema;
    }

    public static class Builder {
        private SchemaBuilder schemaBuilder;

        private Builder(EntityBuilder entityBuilder) {
            this.schemaBuilder = new SchemaBuilder(entityBuilder);
        }

        public Builder dataFetcher(Class<? extends DefaultDataFetcher> datafetcher) {
            schemaBuilder.dataFetcher = datafetcher;
            return this;
        }

        public Builder query(String propertyName, Select<?> query) {
            schemaBuilder.queries.put(propertyName, query);
            return this;
        }

        public GraphQLSchema build() {
            return schemaBuilder.initialize().getGraphQLSchema();
        }
    }
}
