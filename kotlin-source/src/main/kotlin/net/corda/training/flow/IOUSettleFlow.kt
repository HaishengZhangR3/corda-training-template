package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.workflows.asset.CashUtils
import net.corda.finance.workflows.getCashBalance
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import java.util.*

/**
 * This is the flow which handles the (partial) settlement of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUSettleFlow(val linearId: UniqueIdentifier, val amount: Amount<Currency>): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        // settle flow should be triggered by borrower side: to return money back to lender,
        // so counter party is the lender side

        // grab an IOU
        val iouQuery = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val iouToSettle = serviceHub.vaultService.queryBy<IOUState>(iouQuery).states.single()

        // Check the party running this flow is the borrower.
        if (ourIdentity != iouToSettle.state.data.borrower) {
            throw IllegalArgumentException("IOU settlement flow must be initiated by the borrower.")
        }

        // grab the cash state and do rough check
        val cashBalance = serviceHub.getCashBalance(amount.token)
        if (cashBalance < amount) {
            throw IllegalArgumentException("Borrower has only $cashBalance but needs $amount to settle.")
        } else if (amount > (iouToSettle.state.data.amount - iouToSettle.state.data.paid)) {
            throw IllegalArgumentException("Borrower tried to settle with $amount but only needs ${ (iouToSettle.state.data.amount - iouToSettle.state.data.paid) }")
        }

        val counterParty = iouToSettle.state.data.lender
        val participants = listOf(counterParty, ourIdentity).map { it.owningKey }
        val command = Command(IOUContract.Commands.Settle(), participants)

        val notary = iouToSettle.state.notary
        val builder = TransactionBuilder(notary)

        // add cash states to cas command
        val (_, cashKeys) = CashUtils.generateSpend(serviceHub, builder, amount, ourIdentityAndCert, counterParty)

        // Only add an output IOU state of the IOU has not been fully settled.
        val cashRemaining = iouToSettle.state.data.amount - iouToSettle.state.data.paid - amount
//        if (cashRemaining.quantity > 0){
        if (cashRemaining > Amount(0, amount.token)) {
            val iouOutput = iouToSettle.state.data.pay(amount)
            builder.addOutputState(iouOutput)
        }


        builder.addInputState(iouToSettle)
                .addCommand(command)
                .verify(serviceHub)

        // >>> sign myself
        // We need to sign transaction with all keys referred from Cash input states + our public key
        val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
        val signMeTransaction = serviceHub.signInitialTransaction(builder, myKeysToSign)

        // >>> sign counter party
        // Initialising session with other party
        val counterpartySession = initiateFlow(counterParty)

        // Sending other party our identities so they are aware of anonymous public keys
        subFlow(IdentitySyncFlow.Send(counterpartySession, signMeTransaction.tx))

        // Collecting missing signatures
        val signCounterTransaction = subFlow(CollectSignaturesFlow(signMeTransaction, listOf(counterpartySession), myOptionalKeys = myKeysToSign))

        // Finalize the transaction.
        return subFlow(FinalityFlow(signCounterTransaction, counterpartySession))
    }
}

/**
 * This is the flow which signs IOU settlements.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUSettleFlow::class)
class IOUSettleFlowResponder(val flowSession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        // Receiving information about anonymous identities
        subFlow(IdentitySyncFlow.Receive(flowSession))

        // signing transaction
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
            }
        }

        val signedFlow = subFlow(signedTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = signedFlow.id))
    }
}

@InitiatingFlow
@StartableByRPC
/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}