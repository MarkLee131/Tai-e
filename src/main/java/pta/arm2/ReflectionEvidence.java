package pta.arm2;

import java.util.List;

/**
 * Use-site evidence for a {@link FlaggedSite} — the "self-inferencing" signals
 * Elf/SOLAR use to bound a reflective target, gathered to enrich the arm②
 * LLM prompt (and, later, to form the sound type-bound disposer).
 *
 * @param downcastType  fully-qualified type of the post-dominating downcast on
 *                      the site's result, or {@code null} if none
 * @param receiverType  static type of the reflective receiver argument
 *                      (invoke/get/set), or {@code null} if not applicable
 * @param surrounding   a few IR statements around the site, for prompt context
 */
public record ReflectionEvidence(String downcastType,
                                 String receiverType,
                                 List<String> surrounding) {
}
