package com.szh.store.db;

import com.baomidou.mybatisplus.core.toolkit.reflect.IGenericTypeResolver;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * @author demussong
 * @describe 非 Spring 环境下的泛型解析器，替代 MP 默认的 SpringReflectionHelper。
 *           支持沿接口/父类链传递类型变量绑定（如 BaseMapper<T> 中 T 的实际类型），
 *           供 TableInfo 初始化和 BaseMapper 默认方法（如 deleteById）解析 Mapper 的实体泛型
 * @date 2026/9/1
 */
public class StandaloneGenericTypeResolver implements IGenericTypeResolver {

    @Override
    public Class<?>[] resolveTypeArguments(Class<?> clazz, Class<?> genericIfc) {
        Type[] typeArguments = resolve(clazz, genericIfc, new HashMap<>());
        if (typeArguments == null) {
            return null;
        }
        Class<?>[] result = new Class<?>[typeArguments.length];
        for (int i = 0; i < typeArguments.length; i++) {
            result[i] = toClass(typeArguments[i]);
        }
        return result;
    }

    private Type[] resolve(Class<?> clazz, Class<?> genericIfc, Map<TypeVariable<?>, Type> bindings) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            Type[] result = resolveInterface(genericInterface, genericIfc, bindings);
            if (result != null) {
                return result;
            }
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && genericIfc.isAssignableFrom(superClass)) {
            Type genericSuperclass = clazz.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType parameterized) {
                bindTypeParameters(parameterized, bindings);
            }
            return resolve(superClass, genericIfc, bindings);
        }
        return null;
    }

    private Type[] resolveInterface(Type type, Class<?> genericIfc, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof ParameterizedType parameterized) {
            Type rawType = parameterized.getRawType();
            if (rawType == genericIfc) {
                return substitute(parameterized.getActualTypeArguments(), bindings);
            }
            if (rawType instanceof Class<?> rawClass && genericIfc.isAssignableFrom(rawClass)) {
                Map<TypeVariable<?>, Type> newBindings = new HashMap<>(bindings);
                bindTypeParameters(parameterized, newBindings);
                return resolve(rawClass, genericIfc, newBindings);
            }
        } else if (type instanceof Class<?> interfaceClass && genericIfc.isAssignableFrom(interfaceClass)) {
            return resolve(interfaceClass, genericIfc, bindings);
        }
        return null;
    }

    /**
     * 把参数化类型中的实参绑定到对应类的类型变量上，
     * 例如 BaseMapper<AgentEventPO> => T -> AgentEventPO
     */
    private void bindTypeParameters(ParameterizedType parameterized, Map<TypeVariable<?>, Type> bindings) {
        if (!(parameterized.getRawType() instanceof Class<?> rawClass)) {
            return;
        }
        TypeVariable<?>[] typeVariables = rawClass.getTypeParameters();
        Type[] actualArguments = parameterized.getActualTypeArguments();
        for (int i = 0; i < typeVariables.length && i < actualArguments.length; i++) {
            bindings.put(typeVariables[i], substitute(actualArguments[i], bindings));
        }
    }

    private Type[] substitute(Type[] types, Map<TypeVariable<?>, Type> bindings) {
        Type[] result = new Type[types.length];
        for (int i = 0; i < types.length; i++) {
            result[i] = substitute(types[i], bindings);
        }
        return result;
    }

    private Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        while (current instanceof TypeVariable<?> typeVariable) {
            Type bound = bindings.get(typeVariable);
            if (bound == null || bound == current) {
                return null;
            }
            current = bound;
        }
        return current;
    }

    private Class<?> toClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        return null;
    }
}
