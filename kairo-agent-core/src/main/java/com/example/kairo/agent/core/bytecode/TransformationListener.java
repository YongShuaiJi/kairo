package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.TransformationResult;

/**
 * Observer notified after each target-class transformation completes. Listeners
 * run on the retransform thread; they must not perform preview, capture or diff
 * work (those run on dedicated control/diagnostic paths) and must return quickly.
 */
@FunctionalInterface
public interface TransformationListener {

    void onTransformation(TransformationResult result);
}
