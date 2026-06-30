package pta.arm2;

import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

/**
 * A residual reflective site flagged by {@link ReflectionSiteLocator}: a
 * reflective-action call the base analysis could not resolve (empty callee set),
 * i.e. a site where arm②'s LLM-as-annotator should be invoked.
 *
 * @param site      the reflective {@link Invoke} statement
 * @param kind      which reflective API it is
 * @param container the method containing the site
 */
public record FlaggedSite(Invoke site, ReflectiveKind kind, JMethod container) {

    @Override
    public String toString() {
        return kind + "@" + container.getSignature() + "#" + site.getIndex();
    }
}
