/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.test.extensions.junit5;

import io.micronaut.aop.InterceptedProxy;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.FieldInjectionPoint;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.AbstractMicronautExtension;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.*;
import org.junit.platform.commons.support.AnnotationSupport;

import javax.inject.Named;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Extension for JUnit 5.
 *
 * @author graemerocher
 * @since 1.0
 */
public class MicronautJunit5Extension extends AbstractMicronautExtension<ExtensionContext> implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ExecutionCondition, BeforeTestExecutionCallback, AfterTestExecutionCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(MicronautJunit5Extension.class);

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        final Class<?> testClass = extensionContext.getRequiredTestClass();
        final MicronautTest micronautTest = AnnotationSupport.findAnnotation(testClass, MicronautTest.class).orElse(null);
        beforeClass(extensionContext, testClass, micronautTest);
        getStore(extensionContext).put(ApplicationContext.class, applicationContext);
        if (specDefinition != null) {
            TestInstance ti = AnnotationSupport.findAnnotation(testClass, TestInstance.class).orElse(null);
            if (ti != null && ti.value() == TestInstance.Lifecycle.PER_CLASS) {
                Object testInstance = extensionContext.getRequiredTestInstance();
                applicationContext.inject(testInstance);
            }
        }
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        afterClass(extensionContext);
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        final Optional<Object> testInstance = extensionContext.getTestInstance();
        final Optional<? extends AnnotatedElement> testMethod = extensionContext.getTestMethod();
        List<Property> propertyAnnotations = null;
        if (testMethod.isPresent()) {
            Property[] annotationsByType = testMethod.get().getAnnotationsByType(Property.class);
            propertyAnnotations = Arrays.asList(annotationsByType);
        }
        beforeEach(extensionContext, testInstance.orElse(null), testMethod.orElse(null), propertyAnnotations);
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext extensionContext) {
        final Optional<Object> testInstance = extensionContext.getTestInstance();
        if (testInstance.isPresent()) {

            final Class<?> requiredTestClass = extensionContext.getRequiredTestClass();
            if (applicationContext.containsBean(requiredTestClass)) {
                return ConditionEvaluationResult.enabled("Test bean active");
            } else {

                final boolean hasBeanDefinition = isTestSuiteBeanPresent(requiredTestClass);
                if (!hasBeanDefinition) {
                    throw new TestInstantiationException(MISCONFIGURED_MESSAGE);
                } else {
                    return ConditionEvaluationResult.disabled(DISABLED_MESSAGE);
                }

            }
        } else {
            final Class<?> testClass = extensionContext.getRequiredTestClass();
            if (AnnotationSupport.isAnnotated(testClass, MicronautTest.class)) {
                return ConditionEvaluationResult.enabled("Test bean active");
            } else {
                return ConditionEvaluationResult.disabled(DISABLED_MESSAGE);
            }
        }
    }

    @Override
    protected void resolveTestProperties(ExtensionContext context, MicronautTest testAnnotation, Map<String, Object> testProperties) {
        Object o = context.getTestInstance().orElse(null);
        if (o instanceof TestPropertyProvider) {
            Map<String, String> properties = ((TestPropertyProvider) o).getProperties();
            if (CollectionUtils.isNotEmpty(properties)) {
                testProperties.putAll(properties);
            }
        }
    }

    @Override
    protected void alignMocks(ExtensionContext context, Object instance) {
        if (specDefinition != null) {
            for (FieldInjectionPoint injectedField : specDefinition.getInjectedFields()) {
                final boolean isMock = applicationContext.resolveMetadata(injectedField.getType()).isAnnotationPresent(MockBean.class);
                if (isMock) {
                    final Field field = injectedField.getField();
                    field.setAccessible(true);
                    try {
                        final Object mock = field.get(instance);
                        if (mock instanceof InterceptedProxy) {
                            InterceptedProxy ip = (InterceptedProxy) mock;
                            final Object target = ip.interceptedTarget();
                            field.set(instance, target);
                        }
                    } catch (IllegalAccessException e) {
                        // continue
                    }
                }
            }
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        commit();
        rollback();
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        begin();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return applicationContext != null && applicationContext.containsBean(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Named named = parameterContext.findAnnotation(Named.class).orElse(null);
        if (named != null) {
            return applicationContext.getBean(parameterContext.getParameter().getType(), Qualifiers.byName(named.value()));
        } else {
            return applicationContext.getBean(parameterContext.getParameter().getType());
        }
    }

    private static ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }
}
