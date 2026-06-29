package pta.arm3;

/**
 * A defeasible "this method is an identity-transparent wrapper" judgment,
 * mirroring CAFD's identity-transparent-wrapper move. The named method is
 * claimed to simply return (a copy of) its argument, so its per-call-site
 * result should not conflate objects across call sites.
 *
 * <p>This is the actionable counterpart to {@link AliasFact}: an accepted
 * never-alias fact between two alloc-groups, combined with an identity-wrapper
 * judgment, lets the {@link LlmFactPlugin} install a sound-by-consistency
 * pointer filter that de-conflates the wrapper's output.
 *
 * @param method simple name of the wrapper method
 */
public record IdentityWrapperFact(String method) {
}
