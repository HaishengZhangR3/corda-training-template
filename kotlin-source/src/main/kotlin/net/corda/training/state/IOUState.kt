package net.corda.training.state

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.finance.POUNDS
import net.corda.training.contract.IOUContract
import java.util.*
import kotlin.math.acos

/**
 * This is where you'll add the definition of your state object. Look at the unit tests in [IOUStateTests] for
 * instructions on how to complete the [IOUState] class.
 *
 * Remove the "val data: String = "data" property before starting the [IOUState] tasks.
 */
@BelongsToContract(IOUContract::class)
data class IOUState(
        val amount: Amount<Currency>,
        val lender: Party,
        val borrower: Party,
        val paid: Amount<Currency> = Amount(0, amount.token),
        override val linearId: UniqueIdentifier = UniqueIdentifier.fromString(UUID.randomUUID().toString())
): LinearState {
    override val participants: List<Party> get() = listOf(lender, borrower)

    fun pay(amountToPay: Amount<Currency>) = copy(paid = paid.plus(amountToPay))
    fun withNewLender(newLender: Party) = copy(lender = newLender)

}