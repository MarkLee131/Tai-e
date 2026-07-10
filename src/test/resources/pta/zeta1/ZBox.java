/**
 * Slice class whose allocation site is the fixture's fresh site.
 * Its implicit constructor is a resolved callee of {@code ZLib.make}
 * (invokespecial), so call-closure (spec Definition II.24, clause 1)
 * requires a (trivial, empty-effect) summary for {@code ZBox.<init>()}.
 */
public class ZBox {
}
