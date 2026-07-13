package com.example.kairo.api.matrix;

/**
 * Category of a V1.5 compatibility-matrix scenario (&sect;6).
 *
 * <p>The matrix is grouped so the report can be read by dimension: JDK version,
 * agent load mode, framework, ClassLoader shape, proxy kind, special method
 * kind, class lifecycle event, Java module state and source language.
 */
public enum CompatibilityCategory {
    JDK_VERSION,
    LOAD_MODE,
    FRAMEWORK,
    CLASSLOADER,
    PROXY,
    METHOD_KIND,
    LIFECYCLE,
    MODULE,
    LANGUAGE
}
