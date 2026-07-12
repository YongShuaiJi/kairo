package com.example.kairo.agent.core.script;

/**
 * Target type loaded by the test classpath, used to resolve {@code ScriptSession} targets and
 * compile trial scripts against without attaching an agent. The script body in the tests does not
 * reference this type, so its only role is to provide a real, mockable method signature.
 */
public class SessionTarget {

    public String echo(String value) {
        return "origin:" + value;
    }

    public int score(int base) {
        return base * 2;
    }
}
