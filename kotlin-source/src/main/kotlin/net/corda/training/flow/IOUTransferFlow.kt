package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        val command = IOUContract.Commands.Transfer()

        val query = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val inputStateRef = serviceHub.vaultService.queryBy<IOUState>(query).states.single()
        val inputState = inputStateRef.state.data

        if (ourIdentity != inputState.lender) {
            throw IllegalArgumentException("IOU transfer can only be initiated by the IOU lender.")
        }

        val participants = (inputState.participants + newLender).map { it.owningKey }

        // the notary should be the one used when we do "issue"
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
//        val notary = inputStateRef.state.notary
        val builder = TransactionBuilder(notary)
        builder.addCommand(command, participants)
                .addInputState(inputStateRef)
                .addOutputState(inputState.withNewLender(newLender = newLender))
                .verify(serviceHub)

        val signedTransaction = serviceHub.signInitialTransaction(builder)



        val flowSessions = (inputState.participants + newLender - ourIdentity).map{initiateFlow(it)}.toSet()
        val signFlow = subFlow(CollectSignaturesFlow(signedTransaction, flowSessions))
        return subFlow(FinalityFlow(signFlow, flowSessions))

    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }

        val signedFlow = subFlow(signedTransactionFlow)
        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = signedFlow.id))
    }
}