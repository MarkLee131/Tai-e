package pta.arm3;

/**
 * A high-level, <em>defeasible</em> alias fact over two named alloc-groups.
 *
 * <p>{@code mayAlias == true} asserts that the two groups CAN alias
 * (an "observed/derivable alias" in the sound base model). {@code mayAlias
 * == false} is a <em>never-alias</em> proposal: a claim that the two groups
 * never share an object. never-alias proposals are the refinements that the
 * {@link pta.arm3.datalog.ConsistencyEngine} arbitrates: a never-alias fact
 * is rejected when it contradicts the transitive closure of the base alias
 * relation.
 *
 * @param groupA   name of the first alloc-group (e.g. an allocation type name)
 * @param groupB   name of the second alloc-group
 * @param mayAlias {@code true} = base "can-alias" fact; {@code false} = LLM
 *                 never-alias proposal
 */
public record AliasFact(String groupA, String groupB, boolean mayAlias) {
}
