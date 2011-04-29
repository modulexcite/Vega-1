package com.subgraph.vega.ui.http.intercept;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;

import com.subgraph.vega.api.http.proxy.IHttpInterceptor;
import com.subgraph.vega.api.http.proxy.IHttpInterceptorEventHandler;
import com.subgraph.vega.api.http.proxy.IProxyTransaction;
import com.subgraph.vega.api.http.proxy.IProxyTransactionEventHandler;
import com.subgraph.vega.ui.httpeditor.parser.HttpRequestParser;
import com.subgraph.vega.ui.text.httpeditor.RequestRenderer;

public class TransactionManager {
	private IHttpInterceptor interceptor;

	private IHttpInterceptorEventHandler interceptorEventHandler;
	private IProxyTransactionEventHandler transactionEventHandler;
	private IProxyTransaction currentTransaction;
	private IProxyTransaction currentRequestTransaction;
	private TransactionViewer requestViewer;
	private final RequestRenderer requestRenderer = new RequestRenderer();

	private TransactionViewer responseViewer;
	
	TransactionManager(IHttpInterceptor interceptor) {
		interceptorEventHandler = new IHttpInterceptorEventHandler() {
			@Override
			public void notifyQueue(IProxyTransaction transaction) {
				if (transaction.hasResponse() == false) {
					handleTransactionRequest(transaction);
				} else {
					handleTransactionResponse(transaction);
				}
			}
		};
		transactionEventHandler = new IProxyTransactionEventHandler() {
			@Override
			public void notifyForward() {
				handleTransactionForward();
			}

			@Override
			public void notifyComplete(boolean dropped) {
				handleTransactionComplete();
			}
		};
		
		interceptor.setEventHandler(interceptorEventHandler);	
		this.interceptor = interceptor;
	}
	
	void setRequestViewer(TransactionViewer viewer) {
		requestViewer = viewer;
	}
	
	void setResponseViewer(TransactionViewer viewer) {
		responseViewer = viewer;
	}

	void setInactive() {
		setRequestInactive();
		setResponseInactive();
	}	
	
	private void handleTransactionRequest(final IProxyTransaction transaction) {
		synchronized(this) {
			if(currentTransaction == null) {
				currentTransaction = transaction;
				currentTransaction.setEventHandler(transactionEventHandler);
				setRequestPending();
			}
		}
	}

	private void handleTransactionResponse(final IProxyTransaction transaction) {
		synchronized(this) {
			if (currentTransaction == null || currentTransaction == transaction) {
				currentTransaction = transaction;
				setResponsePending();
			}
		}
	}

	private void handleTransactionForward() {
		synchronized(this) {
			if (currentTransaction.hasResponse()) {
				currentTransaction.setEventHandler(null);
				getNextTransaction();
			} else {
				setRequestSent();
			}
		}
	}

	private void handleTransactionComplete() {
		synchronized(this) {
			currentTransaction.setEventHandler(null);
			getNextTransaction();
		}
	}
	
	/**
	 * Must be invoked in a synchronized block.
	 */
	private void getNextTransaction() {
		currentTransaction = interceptor.transactionQueueGet(0);
		if(currentTransaction != null) {
			currentTransaction.setEventHandler(transactionEventHandler);
			if(!currentTransaction.hasResponse()) {
				setRequestPending();
				setResponseInactive();
			} else {
				setResponsePending();
			}
		} else {
			setInactive();
			return;
		}
	}
	
	private synchronized void setRequestPending() {
		final String message = "Request pending to "+ getRequestHostPart(currentTransaction.getRequest());
		final String content = requestRenderer.renderRequestText(currentTransaction.getRequest());
		requestViewer.setStatus(message, true, content);
		currentRequestTransaction = currentTransaction;
	}
	
	private synchronized void setRequestInactive() {
		requestViewer.setStatus("No request pending", false);
		currentRequestTransaction = currentTransaction;
	}

	private synchronized void setRequestSent() {
		final String content = requestRenderer.renderRequestText(currentTransaction.getUriRequest());
		requestViewer.setStatus("Request sent, awaiting response", false, content);
		currentRequestTransaction = currentTransaction;
	}
	
	private String getRequestHostPart(HttpRequest request) {
		URI uri;
		try {
			uri = new URI(request.getRequestLine().getUri());
		} catch (URISyntaxException e) {
			return new String("unknown host - error parsing URI");
		}
		String httpHost = uri.getScheme() + "://" + uri.getHost();
		if (uri.getPort() != -1) {
			httpHost += ":" + uri.getPort();
		}
		return httpHost;
	}

	synchronized void forwardRequest() throws URISyntaxException, UnsupportedEncodingException {
		final HttpRequestParser parser = new HttpRequestParser(currentTransaction.getRequestEngine());
		final HttpUriRequest request = parser.parseRequest(requestViewer.getContent(), currentTransaction.getRequest().getParams().copy());
		if (request != null) {
			currentTransaction.setUriRequest(request);
			currentTransaction.setEventHandler(transactionEventHandler);
			currentTransaction.doForward();
		}
	}

	synchronized void forwardResponse() {
		currentTransaction.doForward();
	}
	
	synchronized void dropRequest() {
		currentTransaction.doDrop();
	}

	synchronized void dropResponse() {
		currentTransaction.doDrop();
	}

	private synchronized void setResponseInactive() {
		responseViewer.setStatus("No response pending", false);
		currentRequestTransaction = null;
	}

	private synchronized void setResponsePending() {
		final String content = requestRenderer.renderResponseText(currentTransaction.getResponse().getRawResponse());
		responseViewer.setStatus("Reponse pending", true, content);
		if(currentRequestTransaction != currentTransaction) {
			setRequestSent();
		}
	}

}
