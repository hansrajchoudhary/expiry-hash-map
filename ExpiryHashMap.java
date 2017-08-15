import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A hashmap which removes keys older than a specified time-to-live (ttl)
 * <p>
 * Created by Hansraj (hansraj.choudhary@) on 28/07/17.
 */
public class ExpiryHashMap<K, V> implements ConcurrentMap<K, V>
{

	/**
	 * onExpiry will be called when a key expires
	 *
	 * @author Hansraj
	 */
	public interface ExpiryCallback<K, V>
	{
		public void onExpiry(K key, V value);
	}

	public static final long DEFAULT_EXPIRY = 30 * 60 * 1000; // 30 minutes

	/**
	 * UID constant for object serialization
	 */
	private static final long serialVersionUID = 3283721294647542043L;

	/**
	 * main map holding key-value pairs
	 */
	private final ConcurrentHashMap<K, V> mainMap;

	/**
	 * supporting map holding key and corresponding expiry tasks
	 */
	private final ConcurrentHashMap<K, KeyExpiryTask> expiryMap;

	/**
	 * timer to run key expiry tasks
	 */
	private final Timer timer = new Timer(true);

	/**
	 * expiry period in ms for all keys
	 */
	private final long globalTtl;

	/**
	 * callback in case of key expiry
	 */
	private final ExpiryCallback<K, V> expiryCallback;

	/**
	 * If true, on any key access, key's expiry period will be increased by globalTtl relative to current time.
	 * If false, key's expiry period will not be increased on key access
	 */
	private final boolean increaseExpiryOnGet;

	/**
	 * This is meaningful only if increaseExpiryOnGet is true otherwise it is ignored. The period is in ms.
	 * If value is less than 1, key's expiry period will be increased everytime on its access.
	 * If value is more than 0, key's expiry period will not be increased for this period from key's expiry task creation time
	 * e.g. If expiry period is 30 min and key k1 is inserted at t1, k1 will expire at t1 + 30 min. If noExpiryExtensionPeriod is specified
	 * as 5 min, then for t1 + 5 min, all k1 accesses will not increase expiry period. k1's expiry period will be increased
	 * on its first access after t1 + 5min
	 * Note: max value of this period is limited to 90% of globalTtl
	 */
	private final long noExpiryExtensionPeriod;

	/**
	 * If true, on any key access, if corresponding value is not null, hits will be incremented else misses will be incremented.
	 * If false, hits and misses will not be counted on key access
	 */
	private final boolean countHitsMisses;

	private final AtomicLong hits;

	private final AtomicLong misses;

	/**
	 * Handles expiry of key's in ExpiryHashMap
	 *
	 * @author Hansraj
	 */
	private class KeyExpiryTask extends TimerTask
	{
		private final K key;

		private boolean isCancelled;

		KeyExpiryTask(K key)
		{
			this.key = key;
			isCancelled = false;
		}

		K getKey()
		{
			return key;
		}

		/**
		 * cancels this expiry task.
		 * This function can be called multiple times safely on same task instance
		 */
		@Override
		public synchronized boolean cancel()
		{
			isCancelled = true;
			// remove from expiry map only if it currently holds this expiry task
			expiryMap.remove(key, this);
			return super.cancel();
		}

		@Override
		public void run()
		{
			V value = null;

			// since this block is synchronized on current task, cancel and this block will execute in serial order for current task
			// either cancel executes first or this block executes first for current task
			synchronized (this)
			{
				if ((!isCancelled) && expiryMap.remove(key, this))
					value = mainMap.remove(key);
			}

			if ((value != null) && (expiryCallback != null))
			{
				try
				{
					expiryCallback.onExpiry(key, value);
				}
				catch (Throwable th)
				{
				}
			}
		}
	}

	/**
	 * Stores creation time of key's expiry task.
	 * It is used when increaseExpiryOnGet is true and noExpiryExtensionPeriod is more than 0
	 *
	 * @author Hansraj
	 */
	private class KeyExpiryTaskWithCT extends KeyExpiryTask
	{
		private final long creationTime;

		KeyExpiryTaskWithCT(K key)
		{
			super(key);
			this.creationTime = System.currentTimeMillis();
		}
	}

	/**
	 * This instance can't be used after this call
	 */
	public void destroy()
	{
		try
		{
			timer.cancel();
			expiryMap.clear();
			mainMap.clear();
		}
		catch (Throwable th)
		{
		}
	}

	//in ms
	public ExpiryHashMap(long ttl)
	{
		this(ttl, null);
	}

	public ExpiryHashMap(long ttl, ExpiryCallback<K, V> expiryCallback)
	{
		this(ttl, expiryCallback, true);
	}

	public ExpiryHashMap(long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet)
	{
		this(ttl, expiryCallback, increaseExpiryOnGet, -1);
	}

	public ExpiryHashMap(long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet, long noExpiryExtensionPeriod)
	{
		this(ttl, expiryCallback, increaseExpiryOnGet, noExpiryExtensionPeriod, false);
	}

	public ExpiryHashMap(long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet, long noExpiryExtensionPeriod, boolean countHitsMisses)
	{
		this.mainMap = new ConcurrentHashMap<K, V>();
		this.expiryMap = new ConcurrentHashMap<K, KeyExpiryTask>();
		this.globalTtl = ((ttl > 0) ? ttl : DEFAULT_EXPIRY);
		this.expiryCallback = expiryCallback;
		this.increaseExpiryOnGet = increaseExpiryOnGet;
		if (increaseExpiryOnGet)
			this.noExpiryExtensionPeriod = ((noExpiryExtensionPeriod > ((long) (this.globalTtl * 0.9))) ? ((long) (this.globalTtl * 0.9)) : noExpiryExtensionPeriod);
		else
			this.noExpiryExtensionPeriod = -1;
		this.countHitsMisses = countHitsMisses;
		this.hits = (countHitsMisses ? new AtomicLong(0) : null);
		this.misses = (countHitsMisses ? new AtomicLong(0) : null);
	}

	public ExpiryHashMap(int initialCapacity, long ttl)
	{
		this(initialCapacity, ttl, null);
	}

	public ExpiryHashMap(int initialCapacity, long ttl, ExpiryCallback<K, V> expiryCallback)
	{
		this(initialCapacity, ttl, expiryCallback, true);
	}

	public ExpiryHashMap(int initialCapacity, long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet)
	{
		this(initialCapacity, ttl, expiryCallback, increaseExpiryOnGet, -1);
	}

	public ExpiryHashMap(int initialCapacity, long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet, long noExpiryExtensionPeriod)
	{
		this(initialCapacity, ttl, expiryCallback, increaseExpiryOnGet, noExpiryExtensionPeriod, false);
	}

	public ExpiryHashMap(int initialCapacity, long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet, long noExpiryExtensionPeriod, boolean countHitsMisses)
	{
		this.mainMap = new ConcurrentHashMap<K, V>(initialCapacity);
		this.expiryMap = new ConcurrentHashMap<K, KeyExpiryTask>(initialCapacity);
		this.globalTtl = ((ttl > 0) ? ttl : DEFAULT_EXPIRY);
		this.expiryCallback = expiryCallback;
		this.increaseExpiryOnGet = increaseExpiryOnGet;
		if (increaseExpiryOnGet)
			this.noExpiryExtensionPeriod = ((noExpiryExtensionPeriod > ((long) (this.globalTtl * 0.9))) ? ((long) (this.globalTtl * 0.9)) : noExpiryExtensionPeriod);
		else
			this.noExpiryExtensionPeriod = -1;
		this.countHitsMisses = countHitsMisses;
		this.hits = (countHitsMisses ? new AtomicLong(0) : null);
		this.misses = (countHitsMisses ? new AtomicLong(0) : null);
	}

	public ExpiryHashMap(int initialCapacity, float loadFactor, int concurrencyLevel, long ttl)
	{
		this(initialCapacity, loadFactor, concurrencyLevel, ttl, null);
	}

	public ExpiryHashMap(int initialCapacity, float loadFactor, int concurrencyLevel, long ttl, ExpiryCallback<K, V> expiryCallback)
	{
		this(initialCapacity, loadFactor, concurrencyLevel, ttl, expiryCallback, true);
	}

	public ExpiryHashMap(int initialCapacity, float loadFactor, int concurrencyLevel, long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet)
	{
		this(initialCapacity, loadFactor, concurrencyLevel, ttl, expiryCallback, increaseExpiryOnGet, -1);
	}

	public ExpiryHashMap(int initialCapacity, float loadFactor, int concurrencyLevel, long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet,
		long noExpiryExtensionPeriod)
	{
		this(initialCapacity, loadFactor, concurrencyLevel, ttl, expiryCallback, increaseExpiryOnGet, noExpiryExtensionPeriod, false);
	}

	public ExpiryHashMap(int initialCapacity, float loadFactor, int concurrencyLevel, long ttl, ExpiryCallback<K, V> expiryCallback, boolean increaseExpiryOnGet,
		long noExpiryExtensionPeriod, boolean countHitsMisses)
	{
		this.mainMap = new ConcurrentHashMap<K, V>(initialCapacity, loadFactor, concurrencyLevel);
		this.expiryMap = new ConcurrentHashMap<K, KeyExpiryTask>(initialCapacity, loadFactor, concurrencyLevel);
		this.globalTtl = ((ttl > 0) ? ttl : DEFAULT_EXPIRY);
		this.expiryCallback = expiryCallback;
		this.increaseExpiryOnGet = increaseExpiryOnGet;
		if (increaseExpiryOnGet)
			this.noExpiryExtensionPeriod = ((noExpiryExtensionPeriod > ((long) (this.globalTtl * 0.9))) ? ((long) (this.globalTtl * 0.9)) : noExpiryExtensionPeriod);
		else
			this.noExpiryExtensionPeriod = -1;
		this.countHitsMisses = countHitsMisses;
		this.hits = (countHitsMisses ? new AtomicLong(0) : null);
		this.misses = (countHitsMisses ? new AtomicLong(0) : null);
	}

	public long getTTL()
	{
		return globalTtl;
	}

	public long getHits()
	{
		return (countHitsMisses ? hits.get() : 0);
	}

	public long getMisses()
	{
		return (countHitsMisses ? misses.get() : 0);
	}

	public float getHitRatio()
	{
		float hitRatio = 0;
		if (countHitsMisses)
		{
			long hit = hits.get();
			long miss = misses.get();
			hitRatio = (((hit + miss) > 0) ? (((float) hit * 100) / (hit + miss)) : -1);
		}

		return hitRatio;
	}

	public ExpiryCallback<K, V> getExpiryCallback()
	{
		return expiryCallback;
	}

	public boolean isIncreaseExpiryOnGet()
	{
		return increaseExpiryOnGet;
	}

	public long getNoExpiryExtensionPeriod()
	{
		return noExpiryExtensionPeriod;
	}

	private void checkKey(K key)
	{
		if (key == null)
			throw new NullPointerException("Key can't be null");
	}

	private void checkValue(V value)
	{
		if (value == null)
			throw new NullPointerException("Value can't be null");
	}

	private boolean cancelExpiryTask(K key)
	{
		return cancelExpiryTask(key, false);
	}

	/**
	 * @param key   whose expiry needs to be cancelled
	 * @param force whether to cancel key's expiry forcefully
	 * @return
	 */
	private boolean cancelExpiryTask(K key, boolean force)
	{
		KeyExpiryTask keyExpiryTask = expiryMap.get(key);
		// Cancel expiry task if:
		// 1. forceful cancellation is requested
		// 2. noExpiryExtensionPeriod is not specified which means we want to always increase expiry. So always cancel expiry task
		// 3. No expiry task is associated with this key. In this case, we want expiry task to associated with key later
		boolean cancelRequired = (force || (noExpiryExtensionPeriod < 1) || (keyExpiryTask == null));
		if (!cancelRequired)
		{
			// comes here when noExpiryExtensionPeriod is specified and expiry task (that tracks its own creation time) is associated with key
			long taskCT = ((KeyExpiryTaskWithCT) keyExpiryTask).creationTime;
			// if noExpiryExtensionPeriod has not passed from expiry task's creation time, we don't need to cancel task
			cancelRequired = ((System.currentTimeMillis() - taskCT) > noExpiryExtensionPeriod);
		}

		if (cancelRequired)
			cancelExpiryTask(keyExpiryTask);

		return cancelRequired;
	}

	private void cancelExpiryTask(KeyExpiryTask keyExpiryTask)
	{
		if (keyExpiryTask != null)
			keyExpiryTask.cancel();
	}

	private void addExpiryTask(K key)
	{
		try
		{
			// if noExpiryExtensionPeriod is not specified, create normal expiry task else create expiry task that tracks its own creation time
			KeyExpiryTask keyExpiryTask = ((noExpiryExtensionPeriod < 1) ? new KeyExpiryTask(key) : new KeyExpiryTaskWithCT(key));
			// Put new expiry task in map and cancel old one
			KeyExpiryTask oldExpiryTask = expiryMap.put(keyExpiryTask.getKey(), keyExpiryTask);
			cancelExpiryTask(oldExpiryTask);
			// Other threads might cancel new expiry task, so synchronize on it and schedule only if it isn't cancelled
			synchronized (keyExpiryTask)
			{
				if (!keyExpiryTask.isCancelled)
					timer.schedule(keyExpiryTask, globalTtl);
			}
		}
		catch (Exception e)
		{
		}
	}

	public V put(K key, V value)
	{
		checkKey(key);
		checkValue(value);
		// forcefully cancel expiry task associated with key if any
		cancelExpiryTask(key, true);

		V oldValue = mainMap.put(key, value);
		// associate new expiry task with the key
		addExpiryTask(key);
		return oldValue;
	}

	public V putIfAbsent(K key, V value)
	{
		checkKey(key);
		checkValue(value);

		V oldValue = mainMap.putIfAbsent(key, value);
		if (oldValue == null)
		{
			// associate new expiry task with the key only if value was mapped with the key
			addExpiryTask(key);
		}
		return oldValue;
	}

	public void putAll(Map<? extends K, ? extends V> map)
	{
		if (map == null)
			return;

		for (Entry<? extends K, ? extends V> entry : map.entrySet())
			put(entry.getKey(), entry.getValue());
	}

	public V get(Object k)
	{
		K key = (K) k;
		checkKey(key);
		boolean expiryTaskCancelled = false;
		if (increaseExpiryOnGet)
		{
			// cancel expiry task associated with key only if reqd and return if task was cancelled
			expiryTaskCancelled = cancelExpiryTask(key);
		}

		V value = mainMap.get(key);
		if ((value != null) && increaseExpiryOnGet && expiryTaskCancelled)
		{
			// value exists for key and old expiry task was cancelled so add new expiry task
			addExpiryTask(key);
		}

		if (countHitsMisses)
		{
			if (value != null)
				hits.incrementAndGet();
			else
				misses.incrementAndGet();
		}
		return value;
	}

	public V remove(Object k)
	{
		K key = (K) k;
		checkKey(key);
		// forcefully cancel expiry task associated with key if any
		cancelExpiryTask(key, true);
		return mainMap.remove(key);
	}

	public boolean remove(Object k, Object v)
	{
		K key = (K) k;
		V value = (V) v;
		checkKey(key);
		checkValue(value);
		KeyExpiryTask oldExpiryTask = expiryMap.get(key);

		boolean removed = mainMap.remove(key, value);
		if (removed)
			cancelExpiryTask(oldExpiryTask);

		return removed;
	}

	public V replace(K key, V value)
	{
		checkKey(key);
		checkValue(value);
		// forcefully cancel expiry task associated with key if any
		cancelExpiryTask(key, true);

		V oldValue = mainMap.replace(key, value);
		if (oldValue != null)
		{
			// if key's value was replaced, associate new expiry task with key
			addExpiryTask(key);
		}
		return oldValue;
	}

	public boolean replace(K key, V oldValue, V newValue)
	{
		checkKey(key);
		checkValue(oldValue);
		checkValue(newValue);
		// forcefully cancel expiry task associated with key if any
		cancelExpiryTask(key, true);

		boolean replaced = mainMap.replace(key, oldValue, newValue);
		if (mainMap.containsKey(key))
			addExpiryTask(key);

		return replaced;
	}

	public void clear()
	{
		throw new UnsupportedOperationException("ExpiryHashMap doesn't support clear operation");
	}

	public boolean containsKey(Object key)
	{
		return mainMap.containsKey(key);
	}

	public boolean containsValue(Object value)
	{
		return mainMap.containsValue(value);
	}

	public boolean isEmpty()
	{
		return mainMap.isEmpty();
	}

	public int size()
	{
		return mainMap.size();
	}

	public Set<Entry<K, V>> entrySet()
	{
		return mainMap.entrySet();
	}

	public Set<K> keySet()
	{
		return mainMap.keySet();
	}

	public Collection<V> values()
	{
		return mainMap.values();
	}

	public int expiryMapSize()
	{
		return expiryMap.size();
	}

	/**
	 * Purges cancelled tasks from Timer object
	 *
	 * @return number of cancelled tasks removed from Timer Q
	 */
	public int purge()
	{
		return timer.purge();
	}

}
