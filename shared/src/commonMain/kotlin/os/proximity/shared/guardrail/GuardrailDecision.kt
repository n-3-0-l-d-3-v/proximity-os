package os.proximity.shared.guardrail

/**
 * The outcome of evaluating a [GuardrailRequest]. There is no fourth
 * option: any request the policy set does not recognize must evaluate to
 * [Deny] by construction, never [Allow] — see docs/GUARDRAIL_POLICY.md.
 */
sealed class GuardrailDecision {

    /** A plain-language reason a non-technical user can read. */
    abstract val reason: String

    data class Allow(override val reason: String) : GuardrailDecision()

    data class Deny(override val reason: String) : GuardrailDecision()

    /**
     * The action is paused pending an explicit user choice.
     * [rememberable] indicates whether the UI should offer to save the
     * user's choice as a new rule.
     */
    data class AskUser(
        override val reason: String,
        val options: List<String> = listOf("Allow", "Deny"),
        val rememberable: Boolean = true
    ) : GuardrailDecision()
}
