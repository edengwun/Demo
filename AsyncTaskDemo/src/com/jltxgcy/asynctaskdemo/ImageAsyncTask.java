package com.jltxgcy.asynctaskdemo;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import android.os.Handler;
import android.os.Message;
import android.os.Process;

public abstract class ImageAsyncTask<Params, Progress, Result> {
	// private static final String LOG_TAG = "ImageAsyncTask";

	private static final int CORE_POOL_SIZE = 6;
	private static final int MAXIMUM_POOL_SIZE = 18;
	private static final int KEEP_ALIVE = 5;

	private static final LinkedBlockingQueue<Runnable> sWorkQueue = new LinkedBlockingQueue<Runnable>(
			12);

	private static final ThreadFactory sThreadFactory = new ThreadFactory() {
		private final AtomicInteger mCount = new AtomicInteger(1);

		public Thread newThread(Runnable r) {
			
			//Log.d("jltxgcy", ""+mCount);
			return new Thread(r, "ImageAsyncTask #" + mCount.getAndIncrement());
		}
	};

//	private static final ThreadPoolExecutor sExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE,
//			MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, sWorkQueue, sThreadFactory,
//			new ThreadPoolExecutor.DiscardOldestPolicy());
	private static ExecutorService pool = Executors.newFixedThreadPool(2);

	private static final int MESSAGE_POST_RESULT = 0x1;
	private static final int MESSAGE_POST_PROGRESS = 0x2;
	private static final int MESSAGE_POST_CANCEL = 0x3;

	private static final InternalHandler sHandler = new InternalHandler();

	private final WorkerRunnable<Params, Result> mWorker;
	private final FutureTask<Result> mFuture;

	private volatile Status mStatus = Status.PENDING;

	public enum Status {
		PENDING,
		RUNNING,
		FINISHED,
	}

	public static void clearQueue() {
		sWorkQueue.clear();
	}

	public ImageAsyncTask() {
		mWorker = new WorkerRunnable<Params, Result>() {
			public Result call() throws Exception {
				Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
				return doInBackground(mParams);//������BitmapWorkerTask�е�doInBackground����
			}
		};

		mFuture = new FutureTask<Result>(mWorker) {
			@SuppressWarnings("unchecked")
			@Override
			protected void done() {//���������߳���
				Message message;
				Result result = null;

				try {
					result = get();//��ȡBitmapWorkerTask��doInBackground����ִ�к��Result
				} catch (InterruptedException e) {
				} catch (ExecutionException e) {//ע���쳣�Ⱥ�˳��
					throw new RuntimeException("An error occured while executing doInBackground()",
							e.getCause());
				} catch (CancellationException e) {//���ȡ���ˣ���ôִ������
					message = sHandler.obtainMessage(MESSAGE_POST_CANCEL,
							new ImageAsyncTaskResult<Result>(ImageAsyncTask.this, (Result[]) null));
					message.sendToTarget();
					return;
				} catch (Throwable t) {
					throw new RuntimeException("An error occured while executing "
							+ "doInBackground()", t);
				}

				message = sHandler.obtainMessage(MESSAGE_POST_RESULT,
						new ImageAsyncTaskResult<Result>(ImageAsyncTask.this, result));
				//����ִ������Ϣ�������߳�
				message.sendToTarget();
			}
		};
	}

	public final Status getStatus() {
		return mStatus;
	}

	protected abstract Result doInBackground(Params... params);

	protected void onPreExecute() {
	}

	protected void onPostExecute(Result result) {
	}

	protected void onProgressUpdate(Progress... values) {
	}

	protected void onCancelled() {
	}

	public final boolean isCancelled() {
		return mFuture.isCancelled();
	}

	public final boolean cancel(boolean mayInterruptIfRunning) {
		return mFuture.cancel(mayInterruptIfRunning);//������ô˷���������done�����з���ȡ����Ϣ��InternalHandler
	}

	public final Result get() throws InterruptedException, ExecutionException {
		return mFuture.get();
	}

	public final Result get(long timeout, TimeUnit unit) throws InterruptedException,
			ExecutionException, TimeoutException {
		return mFuture.get(timeout, unit);
	}

	public final ImageAsyncTask<Params, Progress, Result> execute(Params... params) {
		if (mStatus != Status.PENDING) {
			switch (mStatus) {
			case RUNNING:
				throw new IllegalStateException("Cannot execute task:"
						+ " the task is already running.");
			case FINISHED:
				throw new IllegalStateException("Cannot execute task:"
						+ " the task has already been executed "
						+ "(a task can be executed only once)");
			}
		}

		mStatus = Status.RUNNING;

		onPreExecute();//��һ������

		mWorker.mParams = params;//�Ѳ������˹�ȥ
		pool.execute(mFuture);//������mWorker��call����ִ��

		return this;
	}

	protected final void publishProgress(Progress... values) {
		sHandler.obtainMessage(MESSAGE_POST_PROGRESS,
				new ImageAsyncTaskResult<Progress>(this, values)).sendToTarget();
	}

	private void finish(Result result) {
		onPostExecute(result);//������BitmapWorkerTask�е�onPostExecute����
		mStatus = Status.FINISHED;
	}

	private static class InternalHandler extends Handler {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		@Override
		public void handleMessage(Message msg) {
			ImageAsyncTaskResult result = (ImageAsyncTaskResult) msg.obj;
			switch (msg.what) {
			case MESSAGE_POST_RESULT:
				// There is only one result
				result.mTask.finish(result.mData[0]);
				break;
			case MESSAGE_POST_PROGRESS:
				result.mTask.onProgressUpdate(result.mData);
				break;
			case MESSAGE_POST_CANCEL:
				result.mTask.onCancelled();
				break;
			}
		}
	}

	private static abstract class WorkerRunnable<Params, Result> implements Callable<Result> {
		Params[] mParams;
	}

	private static class ImageAsyncTaskResult<Data> {
		@SuppressWarnings({ "rawtypes" })
		final ImageAsyncTask mTask;
		final Data[] mData;

		@SuppressWarnings({ "rawtypes" })
		ImageAsyncTaskResult(ImageAsyncTask task, Data... data) {
			mTask = task;
			mData = data;
		}
	}
}
