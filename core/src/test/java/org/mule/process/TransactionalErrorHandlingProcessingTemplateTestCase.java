/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.process;

import org.hamcrest.core.Is;
import org.hamcrest.core.IsNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.mule.api.MessagingException;
import org.mule.api.MuleEvent;
import org.mule.api.transaction.ExternalTransactionAwareTransactionFactory;
import org.mule.api.transaction.Transaction;
import org.mule.api.transaction.TransactionConfig;
import org.mule.exception.DefaultMessagingExceptionStrategy;
import org.mule.tck.testmodels.mule.TestTransaction;
import org.mule.tck.testmodels.mule.TestTransactionFactory;
import org.mule.transaction.MuleTransactionConfig;
import org.mule.transaction.TransactionCoordination;
import org.mule.transaction.TransactionTemplateTestUtils;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class TransactionalErrorHandlingProcessingTemplateTestCase extends TransactionalProcessingTemplateTestCase
{

    @Test
    public void testActionNoneAndXaTxAndFailureInCallback() throws Exception
    {
        mockTransaction.setXA(true);
        TransactionCoordination.getInstance().bindTransaction(mockTransaction);
        MuleTransactionConfig config = new MuleTransactionConfig(TransactionConfig.ACTION_NONE);
        ProcessingTemplate processingTemplate = createProcessingTemplate(config);
        MuleEvent mockExceptionListenerResultEvent = configureExceptionListenerCall();
        try
        {
            processingTemplate.execute(getFailureTransactionCallback());
            fail("MessagingException must be thrown");
        }
        catch (MessagingException e)
        {
            assertThat(e, Is.is(mockMessagingException));
            verify(mockMessagingException).setProcessedEvent(mockExceptionListenerResultEvent);
        }

        verify(mockTransaction).suspend();
        verify(mockTransaction, VerificationModeFactory.times(0)).commit();
        verify(mockTransaction,VerificationModeFactory.times(0)).rollback();
        verify(mockTransaction).resume();
    }

    @Test
    public void testActionAlwaysBeginAndSuspendXaTxAndFailureCallback() throws Exception
    {
        TransactionCoordination.getInstance().bindTransaction(mockTransaction);
        mockTransaction.setXA(true);
        MuleTransactionConfig config = new MuleTransactionConfig(TransactionConfig.ACTION_ALWAYS_BEGIN);
        ProcessingTemplate processingTemplate = createProcessingTemplate(config);
        config.setFactory(new TestTransactionFactory(mockNewTransaction));
        MuleEvent exceptionListenerResult = configureExceptionListenerCall();
        try
        {
            processingTemplate.execute(getFailureTransactionCallback());
        }
        catch (MessagingException e)
        {
            assertThat(e, is(mockMessagingException));
            assertThat(e.getEvent(), is(exceptionListenerResult));
        }
        verify(mockTransaction).suspend();
        verify(mockTransaction,VerificationModeFactory.times(0)).commit();
        verify(mockTransaction,VerificationModeFactory.times(0)).rollback();
        verify(mockNewTransaction).rollback();
        verify(mockTransaction).resume();
        assertThat((TestTransaction) TransactionCoordination.getInstance().getTransaction(), is(mockTransaction));
    }

    @Test
    public void testActionAlwaysJoinAndExternalTxAndFailureCallback() throws Exception
    {
        MuleTransactionConfig config = new MuleTransactionConfig(TransactionConfig.ACTION_ALWAYS_JOIN);
        config.setInteractWithExternal(true);
        mockExternalTransactionFactory = mock(ExternalTransactionAwareTransactionFactory.class);
        config.setFactory(mockExternalTransactionFactory);
        when(mockExternalTransactionFactory.joinExternalTransaction(mockMuleContext)).thenAnswer(new Answer<Transaction>()
        {
            @Override
            public Transaction answer(InvocationOnMock invocationOnMock) throws Throwable
            {
                TransactionCoordination.getInstance().bindTransaction(mockTransaction);
                return mockTransaction;
            }
        });
        ProcessingTemplate transactionTemplate = createProcessingTemplate(config);
        MuleEvent exceptionListenerResult = configureExceptionListenerCall();
        try
        {
            transactionTemplate.execute(getFailureTransactionCallback());
        }
        catch (MessagingException e)
        {
            assertThat(e, Is.is(mockMessagingException));
            org.junit.Assert.assertThat(e.getEvent(), Is.is(exceptionListenerResult));
        }
        verify(mockTransaction, VerificationModeFactory.times(0)).commit();
        verify(mockTransaction, VerificationModeFactory.times(1)).rollback();
        assertThat( TransactionCoordination.getInstance().getTransaction(), IsNull.<Object>nullValue());
    }


    @Override
    protected ProcessingTemplate createProcessingTemplate(MuleTransactionConfig config)
    {
        return new TransactionalErrorHandlingProcessingTemplate(mockMuleContext, config, mockMessagingExceptionHandler);
    }

    private MuleEvent configureExceptionListenerCall()
    {
        final MuleEvent mockResultEvent = mock(MuleEvent.class, Answers.RETURNS_DEEP_STUBS.get());
        when(mockMessagingException.getEvent()).thenReturn(mockEvent).thenReturn(mockResultEvent);
        when(mockMessagingExceptionHandler.handleException(mockMessagingException, mockEvent)).thenAnswer(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable
            {
                new DefaultMessagingExceptionStrategy().handleException((Exception)invocationOnMock.getArguments()[0],(MuleEvent)invocationOnMock.getArguments()[1]);
                return mockResultEvent;
            }
        });
        return mockResultEvent;
    }

    protected ProcessingCallback<MuleEvent> getFailureTransactionCallback() throws Exception
    {
        return TransactionTemplateTestUtils.getFailureTransactionCallback(mockMessagingException);
    }

}