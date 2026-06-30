package pta.arm2;

/**
 * Arm② §11 step 2 — builds the rich use-site-evidence LLM prompt for a flagged
 * reflective site, replacing the old siteId-only prompt.
 *
 * <p>The prompt carries the Elf "self-inferencing" signals so the LLM (and the
 * downstream sound type-bound filter) can resolve targets even when class/method
 * names are input-dependent: the post-dominating downcast, the reflective
 * receiver type, and surrounding code. The model is asked for fully-qualified
 * names consistent with those bounds, one per line.
 */
public final class ReflectionPromptBuilder {

    private ReflectionPromptBuilder() {
    }

    public static String build(FlaggedSite site, ReflectionEvidence evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are resolving a Java reflective call that static analysis ")
                .append("could not resolve (the class/method name is input-dependent).\n")
                .append("Reflective operation: ").append(site.kind()).append('\n')
                .append("Enclosing method: ").append(site.container().getSignature()).append('\n');

        if (evidence.downcastType() != null) {
            sb.append("Use-site bound — result is downcast to: ")
                    .append(evidence.downcastType()).append('\n');
        }
        if (evidence.receiverType() != null) {
            sb.append("Use-site bound — reflective receiver type: ")
                    .append(evidence.receiverType()).append('\n');
        }
        if (!evidence.surrounding().isEmpty()) {
            sb.append("Surrounding code:\n");
            for (String stmt : evidence.surrounding()) {
                sb.append("  ").append(stmt).append('\n');
            }
        }

        String asking = switch (site.kind()) {
            case INVOKE -> "the fully-qualified names of the classes whose method may be invoked here";
            case FIELD_GET, FIELD_SET -> "the fully-qualified names of the classes whose field is accessed here";
            default -> "the fully-qualified names of the classes that may be instantiated here";
        };
        sb.append("\nUsing the bounds above (only types consistent with the downcast/")
                .append("receiver type), and any naming/config conventions, list ")
                .append(asking)
                .append(", one fully-qualified class name per line. ")
                .append("If unknown, answer with an empty line.\n");
        return sb.toString();
    }
}
