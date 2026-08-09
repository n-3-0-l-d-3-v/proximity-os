package os.proximity.shared.guardrail

/**
 * A single user-configurable rule. Rules are evaluated in descending
 * [priority] order; the first rule whose [matches] returns true decides the
 * outcome via [decide]. See docs/GUARDRAIL_POLICY.md for the full
 * evaluation order (safety floor, then user rules, then category default).
 */
data class PolicyRule(
    val id: String,
    val description: String,
    val priority: Int = 0,
    val matches: (GuardrailRequest) -> Boolean,
    val decide: (GuardrailRequest) -> GuardrailDecision
)
