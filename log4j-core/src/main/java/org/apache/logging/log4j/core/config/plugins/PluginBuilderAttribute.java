/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

package org.apache.logging.log4j.core.config.plugins;

import org.apache.logging.log4j.plugins.inject.InjectionStrategy;
import org.apache.logging.log4j.core.config.plugins.visitors.PluginBuilderAttributeVisitor;
import org.apache.logging.log4j.util.Strings;

import java.lang.annotation.*;

/**
 * Marks a field as a Plugin Attribute.
 * @deprecated Exists for compatibility with Log4j 2 2.x plugins. Not used for Log4j 2 3.x plugins.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
@InjectionStrategy(PluginBuilderAttributeVisitor.class)
public @interface PluginBuilderAttribute {

    /**
     * Specifies the attribute name this corresponds to. If no attribute is set (i.e., a blank string), then the name
     * of the field (or member) this annotation is attached to will be used.
     */
    String value() default Strings.EMPTY;

    /**
     * Indicates that this attribute is a sensitive one that shouldn't be logged directly. Such attributes will instead
     * be output as a hashed value.
     */
    boolean sensitive() default false;
}
