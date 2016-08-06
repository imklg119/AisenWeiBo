package org.aisen.weibo.sina.ui.widget;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import org.aisen.android.common.context.GlobalContext;
import org.aisen.android.common.utils.BitmapUtil;
import org.aisen.android.common.utils.KeyGenerator;
import org.aisen.android.common.utils.Logger;
import org.aisen.android.component.bitmaploader.core.LruMemoryCache;
import org.aisen.android.network.task.TaskException;
import org.aisen.android.network.task.WorkTask;
import org.aisen.android.support.textspan.ClickableTextViewMentionLinkOnTouchListener;
import org.aisen.android.support.textspan.MyURLSpan;
import org.aisen.weibo.sina.R;
import org.aisen.weibo.sina.base.AppSettings;
import org.aisen.weibo.sina.service.VideoService;
import org.aisen.weibo.sina.support.bean.VideoBean;
import org.aisen.weibo.sina.support.sqlit.EmotionsDB;
import org.aisen.weibo.sina.support.sqlit.SinaDB;
import org.aisen.weibo.sina.ui.widget.span.EmotionSpan;

import java.lang.ref.WeakReference;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载表情，添加链接两个功能<br/>
 * 
 * @author wangdan
 *
 */
public class AisenTextView extends TextView {

	static final String TAG = "AisenTextView";
	
	private static final int CORE_POOL_SIZE = 5;
	/**
	 * 默认执行最大线程是128个
	 */
	private static final int MAXIMUM_POOL_SIZE = 128;
	
	private static final int KEEP_ALIVE = 1;
	
	private static final ThreadFactory sThreadFactory = new ThreadFactory() {
		private final AtomicInteger mCount = new AtomicInteger(1);

		public Thread newThread(Runnable r) {
			int count = mCount.getAndIncrement();
			Logger.v(TAG, "new Thread " + "AisenTextView #" + count);
			return new Thread(r, "AisenTextView #" + count);
		}
	};
	
	/**
	 * 执行队列，默认是10个，超过10个后会开启新的线程，如果已运行线程大于 {@link #MAXIMUM_POOL_SIZE}，执行异常策略
	 */
	private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<Runnable>(10);
	
	private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS,
			sPoolWorkQueue, sThreadFactory);
	
	public static final LruMemoryCache<String, SpannableString> stringMemoryCache = new LruMemoryCache<>(200);

	public static final LruMemoryCache<String, Bitmap> emotionCache = new LruMemoryCache<>(30);

	private static int lineHeight = 0;
	
	private EmotionTask emotionTask;
	
	private String content;
	
	private boolean innerWeb = AppSettings.isInnerBrower();

	private static Bitmap normalURLBitmap;
	private static Bitmap videoURLBitmap;
	
	public AisenTextView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	public AisenTextView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public AisenTextView(Context context) {
		super(context);
	}
	
	public void setContent(String text) {
		boolean replace = false;

		if (!replace)
			replace = innerWeb != AppSettings.isInnerBrower();

		innerWeb = AppSettings.isInnerBrower();
		
		if (!replace && TextUtils.isEmpty(text)) {
			super.setText(text);
			return;
		}
		
		if (!replace && !TextUtils.isEmpty(content) && content.equals(text))
			return;
		
		content = text;
		
		if (emotionTask != null)
			emotionTask.cancel(true);
		
		String key = KeyGenerator.generateMD5(text);
		SpannableString spannableString = stringMemoryCache.get(key);
		if (spannableString != null) {
			Logger.v(TAG, "从内存中加载spannable数据");
			
			super.setText(spannableString);
		} else {
			Logger.v(TAG, "开启线程，开始加载spannable数据");
			
			super.setText(text);
			emotionTask = new EmotionTask(this);
			emotionTask.executeOnExecutor(THREAD_POOL_EXECUTOR);
		}
		
		setClickable(false);
		setOnTouchListener(onTouchListener);
	}
	
	static class EmotionTask extends WorkTask<Void, SpannableString, Boolean> {

        WeakReference<TextView> textViewRef;

        EmotionTask(TextView textView) {
            textViewRef = new WeakReference<TextView>(textView);
        }

		@Override
		public Boolean workInBackground(Void... params) throws TaskException {
			Resources res = GlobalContext.getInstance().getResources();
			int bitmapSize = res.getDimensionPixelSize(R.dimen.emotion_size);
			if (normalURLBitmap == null) {
				normalURLBitmap = BitmapFactory.decodeResource(res, R.drawable.timeline_card_small_web);
				normalURLBitmap = BitmapUtil.zoomBitmap(normalURLBitmap, bitmapSize);
			}
			if (videoURLBitmap == null) {
				videoURLBitmap = BitmapFactory.decodeResource(res, R.drawable.timeline_card_small_video);
				videoURLBitmap = BitmapUtil.zoomBitmap(videoURLBitmap, bitmapSize);
			}

            TextView textView = textViewRef.get();
            if (textView == null)
                return false;

			if (TextUtils.isEmpty(textView.getText()))
				return false;

			int lineH = 0;
			while (lineH == 0) {
				lineH = textView.getLineHeight();
			}
			if (lineHeight != lineH) {
				emotionCache.evictAll();
				lineHeight = lineH;
			}

			// android.view.ViewRootImpl$CalledFromWrongThreadException Only the original thread that created a view hierarchy can touch its views.
			// 把getText + 一个空字符试试，可能是直接取值会刷UI
			String text = textView.getText() + "";
			SpannableString spannableString = SpannableString.valueOf(text);
			Matcher localMatcher = Pattern.compile("\\[(\\S+?)\\]").matcher(spannableString);
			while (localMatcher.find()) {
				if (isCancelled())
					break;
				
				String key = localMatcher.group(0);

				int k = localMatcher.start();
				int m = localMatcher.end();

				byte[] data = EmotionsDB.getEmotion(key);
				if (data == null)
					continue;

				synchronized (emotionCache) {
					Bitmap bitmap = emotionCache.get(key);
					if (bitmap == null) {
						bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
						bitmap = BitmapUtil.zoomBitmap(bitmap, lineHeight);

						// 添加到内存中
						emotionCache.put(key, bitmap);
					}

					EmotionSpan l = new EmotionSpan(GlobalContext.getInstance(), bitmap, ImageSpan.ALIGN_BASELINE);
					spannableString.setSpan(l, k, m, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				}
			}
			
			// 用户名称
//			Pattern pattern = Pattern.compile("@([a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+)");
			Pattern pattern = Pattern.compile("@[\\w\\p{InCJKUnifiedIdeographs}-]{1,26}");
			String scheme = "org.aisen.weibo.sina.userinfo://";
			Linkify.addLinks(spannableString, pattern, scheme);

			// 网页链接
			scheme = "http://";
			// 启用内置浏览器
			if (AppSettings.isInnerBrower())
				scheme = "aisen://";
			Linkify.addLinks(spannableString, Pattern.compile("http://[a-zA-Z0-9+&@#/%?=~_\\-|!:,\\.;]*[a-zA-Z0-9+&@#/%=~_|]"), scheme);

			// 话题
			Pattern dd = Pattern.compile("#[\\p{Print}\\p{InCJKUnifiedIdeographs}&&[^#]]+#");
			//Pattern dd = Pattern.compile("#([a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+)#");
			scheme = "org.aisen.weibo.sina.topics://";
			Linkify.addLinks(spannableString, dd, scheme);

			URLSpan[] urlSpans = spannableString.getSpans(0, spannableString.length(), URLSpan.class);
			Object weiboSpan = null;
			for (URLSpan urlSpan : urlSpans) {
				int start = spannableString.getSpanStart(urlSpan);
				int end = spannableString.getSpanEnd(urlSpan);
				try {
					spannableString.removeSpan(urlSpan);
				} catch (Exception e) {
				}

				Uri uri = Uri.parse(urlSpan.getURL());
				String id = KeyGenerator.generateMD5(uri.toString().replace("aisen://", ""));
				VideoBean videoBean = SinaDB.getDB().selectById(null, VideoBean.class, id);
				if (videoBean != null) {
					if (videoBean.getType() == VideoService.TYPE_VIDEO_SINA ||
							videoBean.getType() == VideoService.TYPE_VIDEO_WEIPAI) {
						weiboSpan = new ImageSpan(GlobalContext.getInstance(), videoURLBitmap, ImageSpan.ALIGN_BASELINE);

						Logger.d(TAG, "id[%s], url[%s], video", id, urlSpan.getURL());
					}
					else {
						weiboSpan = new ImageSpan(GlobalContext.getInstance(), normalURLBitmap, ImageSpan.ALIGN_BASELINE);

						Logger.d(TAG, "id[%s], url[%s], normal", id, urlSpan.getURL());
					}
				}
				else {
					Logger.d(TAG, "id[%s], url[%s], none", id, urlSpan.getURL());

					weiboSpan = new MyURLSpan(urlSpan.getURL());
				}

				spannableString.setSpan(weiboSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			
			publishProgress(spannableString);
			
			String key = KeyGenerator.generateMD5(spannableString.toString());
			stringMemoryCache.put(key, spannableString);
			Logger.v(TAG, String.format("添加spannable到内存中，现在共有%d个spannable", stringMemoryCache.size()));
			return null;
		}
		
		@Override
		protected void onProgressUpdate(SpannableString... values) {
			super.onProgressUpdate(values);

            TextView textView = textViewRef.get();
            if (textView == null)
                return;
			
			try {
				if (values != null && values.length > 0)
					textView.setText(values[0]);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
	}

	private OnTouchListener onTouchListener = new OnTouchListener() {

		ClickableTextViewMentionLinkOnTouchListener listener = new ClickableTextViewMentionLinkOnTouchListener();

		@Override
		public boolean onTouch(View v, MotionEvent event) {
			return listener.onTouch(v, event);

		}
	};

}
