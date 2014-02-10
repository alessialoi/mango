package cc.concurrent.mango.operator;

import cc.concurrent.mango.*;
import cc.concurrent.mango.exception.structure.CacheByAnnotationException;
import cc.concurrent.mango.exception.structure.IncorrectReturnTypeException;
import cc.concurrent.mango.exception.structure.IncorrectSqlException;
import cc.concurrent.mango.exception.structure.NoSqlAnnotationException;
import cc.concurrent.mango.jdbc.BeanPropertyRowMapper;
import cc.concurrent.mango.jdbc.JdbcUtils;
import cc.concurrent.mango.jdbc.RowMapper;
import cc.concurrent.mango.jdbc.SingleColumnRowMapper;
import cc.concurrent.mango.runtime.TypeContext;
import cc.concurrent.mango.runtime.TypeContextImpl;
import cc.concurrent.mango.runtime.parser.ASTRootNode;
import cc.concurrent.mango.runtime.parser.Parser;
import com.google.common.base.Strings;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Operator工厂
 *
 * @author ash
 */
public class OperatorFactory {

    private final static Pattern INSERT_PATTERN = Pattern.compile("^\\s*INSERT\\s+", Pattern.CASE_INSENSITIVE);
    private final static Pattern DELETE_PATTERN = Pattern.compile("^\\s*DELETE\\s+", Pattern.CASE_INSENSITIVE);
    private final static Pattern UPDATE_PATTERN = Pattern.compile("^\\s*UPDATE\\s+", Pattern.CASE_INSENSITIVE);
    private final static Pattern SELECT_PATTERN = Pattern.compile("^\\s*SELECT\\s+", Pattern.CASE_INSENSITIVE);


    /**
     * 获取Operator
     *
     * @param method
     * @return
     * @throws Exception
     */
    public static Operator getOperator(Method method) throws Exception {
        SQL sqlAnno = method.getAnnotation(SQL.class);
        if (sqlAnno == null) {
            throw new NoSqlAnnotationException("expected cc.concurrent.mango.SQL annotation on method");
        }
        String sql = sqlAnno.value();
        if (Strings.isNullOrEmpty(sql)) {
            throw new IncorrectSqlException("sql is null or empty");
        }
        ASTRootNode rootNode = new Parser(sql).parse();
        SQLType sqlType = getSQLType(sql);
        if (sqlType == null) {
            throw new IncorrectSqlException("sql must start with INSERT or DELETE or UPDATE or SELECT");
        }

        TypeContext context = getTypeContext(method);
        rootNode.checkType(context); // 监测参数类型

        if (sqlType == SQLType.SELECT) {
            return buildQueryOperator(method, rootNode);
        } else if (int.class.equals(method.getReturnType())) {
            return buildUpdateOperator(method, rootNode, sqlType);
        } else if (int[].class.equals(method.getReturnType())) {
            return buildBatchUpdateOperator(method, rootNode);
        } else {
            throw new IncorrectReturnTypeException("return type expected int or int[] but " + method.getReturnType());
        }
    }


    /**
     * 构建查询
     *
     * @param method
     * @param rootNode
     * @return
     */
    private static Operator buildQueryOperator(Method method, ASTRootNode rootNode) {
        Class<?> mappedClass = null;
        boolean isForList = false;
        boolean isForSet = false;
        boolean isForArray = false;
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof ParameterizedType) { // 参数化类型
            ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class) {
                Class<?> rawClass = (Class<?>) rawType;
                if (List.class.equals(rawClass)) {
                    isForList = true;
                    Type typeArgument = parameterizedType.getActualTypeArguments()[0];
                    if (typeArgument instanceof Class) {
                        mappedClass = (Class<?>) typeArgument;
                    }
                } else if (Set.class.equals(rawClass)) {
                    isForSet = true;
                    Type typeArgument = parameterizedType.getActualTypeArguments()[0];
                    if (typeArgument instanceof Class) {
                        mappedClass = (Class<?>) typeArgument;
                    }
                }
            }
        } else if (genericReturnType instanceof Class) { // 没有参数化
            Class<?> clazz = (Class<?>) genericReturnType;
            if (clazz.isArray()) { // 数组
                isForArray = true;
                mappedClass = clazz.getComponentType();
            } else { // 普通类
                mappedClass = clazz;
            }
        }
        if (mappedClass == null) {
            throw new IncorrectReturnTypeException("return type " + method.getGenericReturnType() + " is error");
        }

        Operator operator = new QueryOperator(rootNode, getRowMapper(mappedClass), isForList, isForSet, isForArray);
        CacheDescriptor cacheDescriptor = getCacheDescriptor(method);
        operator.setCacheDescriptor(cacheDescriptor);
        //TODO 添加构造函数验证与method参数验证

        return operator;
    }

    /**
     * 构建更新
     *
     * @param method
     * @param rootNode
     * @return
     */
    private static UpdateOperator buildUpdateOperator(Method method, ASTRootNode rootNode, SQLType sqlType) {
        ReturnGeneratedId returnGeneratedIdAnno = method.getAnnotation(ReturnGeneratedId.class);
        boolean returnGeneratedId = returnGeneratedIdAnno != null // 要求返回自增id
                && sqlType == SQLType.INSERT; // 是插入语句
        return new UpdateOperator(rootNode, returnGeneratedId);
    }

    /**
     * 构建批量更新
     *
     * @param method
     * @param rootNode
     * @return
     */
    private static BatchUpdateOperator buildBatchUpdateOperator(Method method, ASTRootNode rootNode) {
        return new BatchUpdateOperator(rootNode);
    }

    private static SQLType getSQLType(String sql) {
        if (INSERT_PATTERN.matcher(sql).find()) {
            return SQLType.INSERT;
        } else if (DELETE_PATTERN.matcher(sql).find()) {
            return SQLType.DELETE;
        } else if (UPDATE_PATTERN.matcher(sql).find()) {
            return SQLType.UPDATE;
        } else if (SELECT_PATTERN.matcher(sql).find()) {
            return SQLType.SELECT;
        }
        return null;
    }

    private static <T> RowMapper<T> getRowMapper(Class<T> clazz) {
        return JdbcUtils.isSingleColumnClass(clazz) ?
                new SingleColumnRowMapper<T>(clazz) :
                new BeanPropertyRowMapper<T>(clazz);
    }

    private static CacheDescriptor getCacheDescriptor(Method method) {
        Class<?> daoClass = method.getDeclaringClass();
        Cache cacheAnno = daoClass.getAnnotation(Cache.class);
        CacheDescriptor cacheDescriptor = new CacheDescriptor();
        if (cacheAnno != null) { // dao类使用cache
            CacheIgnored cacheIgnoredAnno = method.getAnnotation(CacheIgnored.class);
            if (cacheIgnoredAnno == null) { // method不禁用cache
                cacheDescriptor.setUseCache(true);
                cacheDescriptor.setPrefix(cacheAnno.prefix());
                Annotation[][] pass = method.getParameterAnnotations();
                int num = 0;
                for (int i = 0; i < pass.length; i++) {
                    Annotation[] pas = pass[i];
                    for (Annotation pa : pas) {
                        if (CacheBy.class.equals(pa.annotationType())) {
                            cacheDescriptor.setBeanName(String.valueOf(i + 1));
                            cacheDescriptor.setPropertyName(((CacheBy) pa).value());
                            num++;
                        }
                    }
                }
                if (num != 1) { //TODO 合适得异常处理
                    throw new CacheByAnnotationException("need 1 but " + num);
                }
            }
        }
        return cacheDescriptor;
    }

    private static TypeContext getTypeContext(Method method) {
        Map<String, Class<?>> parameterTypeMap = new HashMap<String, Class<?>>();
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeMap.put(String.valueOf(i + 1), parameterTypes[i]);
        }
        return new TypeContextImpl(parameterTypeMap);
    }

}