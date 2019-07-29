/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.swagger.generator.core.utils;

import java.lang.reflect.*;
import java.util.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.servicecomb.swagger.extend.ModelResolverExt;
import org.apache.servicecomb.swagger.generator.core.OperationGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;

import io.swagger.converter.ModelConverters;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.AbstractSerializableParameter;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.PropertyBuilder;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;

public final class ParamUtils {
  private static DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

  private static Map<String, String> classNameMap = new HashMap<>();

  private static boolean isMergeMode = false;

  private static final Logger LOGGER = LoggerFactory.getLogger(ParamUtils.class);

  private ParamUtils() {

  }

  public static void setIsMergeMode(boolean mode) {
    isMergeMode = mode;
  }

  // 如果existName为empty，则通过原型查找
  public static String getParameterName(String existName, Method method, int paramIdx) {
    if (StringUtils.isEmpty(existName)) {
      existName = getParameterName(method, paramIdx);
    }

    return existName;
  }

  public static String getParameterName(Executable methodOrConstructor, int parameterIndex) {
    MethodParameter methodParameter = MethodParameter.forMethodOrConstructor(methodOrConstructor, parameterIndex);
    return getParameterName(methodParameter, parameterIndex);
  }

  public static String getParameterName(Method method, int paramIdx) {
    MethodParameter methodParameter = new MethodParameter(method, paramIdx);
    return getParameterName(methodParameter, paramIdx);
  }

  public static String getParameterName(MethodParameter methodParameter, int paramIdx) {
    methodParameter.initParameterNameDiscovery(parameterNameDiscoverer);

    String paramName = methodParameter.getParameterName();
    if (paramName == null) {
      // 小于jdk8的场景中，即使有debug参数，也无法对着interface获取参数名，此时直接使用arg + paramIndex来表示
      paramName = "arg" + paramIdx;
    }
    return paramName;
  }

  public static Type getGenericParameterType(Method method, int paramIdx) {
    return method.getGenericParameterTypes()[paramIdx];
  }

  public static String generateBodyParameterName(Method method) {
    return method.getName() + "Body";
  }

  public static BodyParameter createBodyParameter(OperationGenerator operationGenerator,
      int paramIdx) {
    Method method = operationGenerator.getProviderMethod();
    String paramName = getParameterName(method, paramIdx);
    Type paramType = getGenericParameterType(method, paramIdx);
    return createBodyParameter(operationGenerator.getSwagger(), paramName, paramType);
  }

  public static BodyParameter createBodyParameter(Swagger swagger, String paramName, Type paramType) {
    addDefinitions(swagger, paramType);

    Property property = ModelConverters.getInstance().readAsProperty(paramType);
    Model model = PropertyBuilder.toModel(property);
    if (model instanceof ModelImpl && property instanceof StringProperty) {
      ((ModelImpl) model).setEnum(((StringProperty) property).getEnum());
    }

    BodyParameter bodyParameter = new BodyParameter();
    bodyParameter.setName(paramName);
    bodyParameter.setSchema(model);

    return bodyParameter;
  }

  public static void addDefinitions(Swagger swagger, Type paramType) {
    if (swagger.getDefinitions() == null) {
      ModelResolverExt.refreshClassNameMap();
    }
    Map<String, Model> models = ModelConverters.getInstance().readAll(paramType);
    for (Map.Entry<String, Model> entry : models.entrySet()) {
//      if (!isMergeMode && models.size() == 1) {
//        handleRepeat(entry.getKey(), paramType);
//      }
      swagger.addDefinition(entry.getKey(), entry.getValue());
    }
  }

  private static void handleRepeat(String key, Type paramType) {
    Type[] packageClass = {Integer.class, Double.class, Float.class, Character.class, Boolean.class, String.class,
        Long.class, Short.class, Number.class, Byte.class};
    if (paramType instanceof Class) {
      if (((Class) paramType).isArray()) {
        Field[] fields = ((Class) paramType).getDeclaredFields();
        if (fields != null && fields.length > 0) {
          Type temType = fields[0].getClass();
          handleRepeat(key, temType);
        }
      } else if (((Class) paramType).isPrimitive()) {
        return;
      } else {
        for (Type c : packageClass) {
          if (paramType.equals(c)) {
            return;
          }
        }
        checkRepeat(key, paramType.getTypeName());
      }
    } else if (paramType instanceof ParameterizedType) {
      Type collectionsType = ((ParameterizedType) paramType).getRawType();
      if (collectionsType.equals(List.class) || collectionsType.equals(Map.class) ||
          collectionsType.equals(Set.class) || collectionsType.equals(Queue.class)) {
        Type[] temType = ((ParameterizedType) paramType).getActualTypeArguments();
        if (temType != null && temType.length > 0) {
          for (Type t : temType) {
            handleRepeat(key, t);
          }
        }
      }
    } else if (paramType instanceof GenericArrayType) {
      handleRepeat(key, ((GenericArrayType) paramType).getGenericComponentType());
    }
  }

  private static void checkRepeat(String key, String type) {
    if (classNameMap.containsKey(key)) {
      if (classNameMap.get(key) != null && !classNameMap.get(key).equals(type)) {
        LOGGER.warn("The class " + type + " and the class " + classNameMap.get(key) + " have the same APIModel value");
      }
    } else {
      classNameMap.put(key, type);
    }
  }

  public static void setParameterType(Swagger swagger, Method method, int paramIdx,
      AbstractSerializableParameter<?> parameter) {
    Type paramType = ParamUtils.getGenericParameterType(method, paramIdx);

    ParamUtils.addDefinitions(swagger, paramType);

    Property property = ModelConverters.getInstance().readAsProperty(paramType);

    if (isComplexProperty(property)) {
      // cannot set a simple parameter(header, query, etc.) as complex type
      String msg = String.format("not allow complex type for %s parameter, method=%s:%s, paramIdx=%d, type=%s",
          parameter.getIn(),
          method.getDeclaringClass().getName(),
          method.getName(),
          paramIdx,
          paramType.getTypeName());
      throw new Error(msg);
    }
    parameter.setProperty(property);
  }

  /**
   * Set param type info. For {@linkplain javax.ws.rs.BeanParam BeanParam} scenario.
   *
   * @param paramType type of the swagger parameter
   * @param parameter swagger parameter
   */
  public static void setParameterType(Type paramType, AbstractSerializableParameter<?> parameter) {
    Property property = ModelConverters.getInstance().readAsProperty(paramType);

    if (isComplexProperty(property)) {
      // cannot set a simple parameter(header, query, etc.) as complex type
      throw new IllegalArgumentException(
          String.format(
              "not allow such type of param:[%s], param name is [%s]",
              property.getClass(),
              parameter.getName()));
    }
    parameter.setProperty(property);
  }

  public static boolean isComplexProperty(Property property) {
    if (RefProperty.class.isInstance(property) || ObjectProperty.class.isInstance(property)
        || MapProperty.class.isInstance(property)) {
      return true;
    }

    if (ArrayProperty.class.isInstance(property)) {
      return isComplexProperty(((ArrayProperty) property).getItems());
    }

    return false;
  }

  public static int findParameterByName(String name, List<Parameter> parameterList) {
    for (int idx = 0; idx < parameterList.size(); idx++) {
      Parameter parameter = parameterList.get(idx);
      if (name.equals(parameter.getName())) {
        return idx;
      }
    }

    return -1;
  }

  public static boolean isRealBodyParameter(Parameter parameter) {
    return BodyParameter.class.getName().equals(parameter.getClass().getName());
  }
}
