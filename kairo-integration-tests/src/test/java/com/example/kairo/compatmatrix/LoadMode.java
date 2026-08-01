package com.example.kairo.compatmatrix;

/**
 * The agent load mode for one V1.7 compatibility scenario (&sect;10.1 &ldquo;加载&rdquo;
 * column). The enum is the normalized form; the frozen catalog also retains the
 * verbatim &sect;10.1 string via {@code loadModeRaw} so the contract cannot drift
 * from the roadmap table.
 */
public enum LoadMode {

    /** {@code -javaagent} before {@code main}: C01, C03, C05, C06, C07, C08, C10. */
    PREMAIN,
    /** External attach via {@code agentmain}; the &sect;10.1 cell reads &ldquo;external attach/agentmain&rdquo; (C02). */
    EXTERNAL_ATTACH_AGENTMAIN,
    /** External attach; the &sect;10.1 cell reads &ldquo;external attach&rdquo; (C04). */
    EXTERNAL_ATTACH,
    /** External attach via {@code agentmain}; the &sect;10.1 cell reads &ldquo;agentmain&rdquo; (C09). */
    AGENTMAIN;

    /** The verbatim &sect;10.1 &ldquo;加载&rdquo; cell for this normalized mode. */
    public String raw() {
        return switch (this) {
            case PREMAIN -> "premain";
            case EXTERNAL_ATTACH_AGENTMAIN -> "external attach/agentmain";
            case EXTERNAL_ATTACH -> "external attach";
            case AGENTMAIN -> "agentmain";
        };
    }
}
