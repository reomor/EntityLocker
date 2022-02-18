package com.example.almworks.locker;

import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class EntityLockerImpl<ID> implements EntityLocker<ID> {

  private static final int DEFAULT_GLOBAL_LOCK_ESCALATION_THRESHOLD = 10;

  // provide lock for atomic operation on all objects
  private final ReentrantLock innerLock;
  private final Map<Class<?>, ReentrantLock> clazzGlobalLocks;
  private final Map<Class<?>, Condition> clazzGlobalLocksConditions;
  private final Map<Class<?>, Map<ID, ReentrantLock>> entitiesLockMaps;
  private final Map<Long, Map<Class<?>, Set<ID>>> threadLockedEntities;
  private final Map<Class<?>, AtomicInteger> clazzNumberOfLockedObjects;
  private final int globalEscalationThreshold;

  public EntityLockerImpl() {
    this(DEFAULT_GLOBAL_LOCK_ESCALATION_THRESHOLD);
  }

  public EntityLockerImpl(int globalEscalationThreshold) {

    this.innerLock = new ReentrantLock();

    this.clazzGlobalLocks = new HashMap<>();
    this.clazzGlobalLocksConditions = new HashMap<>();

    this.entitiesLockMaps = new HashMap<>();
    this.threadLockedEntities = new HashMap<>();
    this.clazzNumberOfLockedObjects = new HashMap<>();

    this.globalEscalationThreshold = globalEscalationThreshold;
  }

  @Override
  public boolean globalLock(Class<?> clazz) throws InterruptedException {

    innerLock.lock();

    ReentrantLock classGlobalLock;
    try {
      classGlobalLock = getOrCreateClassGlobalLock(clazz);
    } finally {
      innerLock.unlock();
    }

    // block class or wait
    classGlobalLock.lockInterruptibly();
    Condition classGlobalLockCondition = getClassGlobalLockCondition(clazz);

    while (globalLockIsNotPossibleForThread(clazz)) {
      // then it's not possible to get global lock
      classGlobalLockCondition.await();
    }

    return true;
  }

  @Override
  public void globalUnlock(Class<?> clazz) {

    innerLock.lock();

    try {
      ReentrantLock classGlobalLock = getCurrentClassGlobalLock(clazz);
      if (classGlobalLock != null) {
        classGlobalLock.unlock();
      }
    } finally {
      innerLock.unlock();
    }
  }

  @Override
  public boolean lock(@NonNull ID entityId, Class<?> clazz) throws InterruptedException {

    innerLock.lock();

    ReentrantLock classGlobalLock = getOrCreateClassGlobalLock(clazz);

    classGlobalLock.lock();

    ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

    // try to get a global lock
    if (escalationConditionsFulfilled(clazz)) {
        // success - free all locked, hold lock and return
        unlockLockedByThread(clazz);
        return true;
    }

    // fail - continue with separate lock
    entityLock.lockInterruptibly();
    postLockActions(clazz);

    classGlobalLock.unlock();
    innerLock.unlock();

    return true;
  }

  @Override
  public boolean lock(ID entityId, Class<?> clazz, long timeout, TimeUnit timeUnit) throws InterruptedException {

    long startTimeInBaseUnit = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);

    innerLock.lock();

    ReentrantLock classGlobalLock;
    try {
      classGlobalLock = getOrCreateClassGlobalLock(clazz);
    } finally {
      innerLock.unlock();
    }

    if (!classGlobalLock.tryLock(timeout, timeUnit)) {
      return false;
    }

    ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

    long endTimeInBaseUnit = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    long lastTimeout = startTimeInBaseUnit + timeout - endTimeInBaseUnit;

    if (!entityLock.tryLock(lastTimeout, timeUnit)) {
      return false;
    }

    // here we have a global lock and entity lock
    // check escalation conditions
    if (escalationConditionsFulfilled(clazz)) {
      // success - free all locked, hold global lock and return
      unlockLockedByThread(clazz);
      return true;
    }

    postLockActions(clazz);
    classGlobalLock.unlock();
    return true;
  }

  @Override
  public void unlock(@NonNull ID entityId, Class<?> clazz) {
    unlockEntity(entityId, clazz);
  }

  @ThreadSafeIMHO
  private void postLockActions(Class<?> clazz) {
    getNumberOfBlockedObjects(clazz).incrementAndGet();
  }

  @ThreadSafeIMHO
  private void postUnlockActions(Class<?> clazz) {
    // lock because of signal
    innerLock.lock();
    ReentrantLock globalLock = getOrCreateClassGlobalLock(clazz);
    globalLock.lock();
    try {
      getNumberOfBlockedObjects(clazz).decrementAndGet();
      getClassGlobalLockCondition(clazz).signalAll();
    } finally {
      globalLock.unlock();
      innerLock.unlock();
    }
  }

  @ThreadSafeIMHO
  private boolean escalationConditionsFulfilled(Class<?> clazz) {
    innerLock.lock();
    try {
      int lockedByThreadEntities = getNumberOfLockedByThreadEntities(clazz);
      // check escalation conditions
      return lockedByThreadEntities >= globalEscalationThreshold && !globalLockIsNotPossibleForThread(clazz);
    } finally {
      innerLock.unlock();
    }
  }

  @ThreadSafeIMHO
  private boolean globalLockIsNotPossibleForThread(Class<?> clazz) {
    innerLock.lock();
    try {
      // there must be not blocked entities at all
      return getNumberOfBlockedObjects(clazz).get() != 0
        // or all of them are blocked by the same thread
        && getNumberOfBlockedObjects(clazz).get() != getNumberOfLockedByThreadEntities(clazz);
    } finally {
      innerLock.unlock();
    }
  }

  @NotNull
  private AtomicInteger getNumberOfBlockedObjects(Class<?> clazz) {
    innerLock.lock();
    try {
      clazzNumberOfLockedObjects.computeIfAbsent(clazz, ignore -> new AtomicInteger(0));
      return clazzNumberOfLockedObjects.get(clazz);
    } finally {
      innerLock.unlock();
    }
  }

  @NotNull
  private ReentrantLock getOrCreateClassGlobalLock(Class<?> clazz) {
    innerLock.lock();
    try {
      ReentrantLock lock = clazzGlobalLocks.computeIfAbsent(clazz, ignore -> new ReentrantLock());
      clazzGlobalLocksConditions.computeIfAbsent(clazz, ignore -> lock.newCondition());
      return lock;
    } finally {
      innerLock.unlock();
    }
  }

  @Nullable
  protected ReentrantLock getCurrentClassGlobalLock(Class<?> clazz) {
    innerLock.lock();
    try {
      getOrCreateClassGlobalLock(clazz);
      return clazzGlobalLocks.get(clazz);
    } finally {
      innerLock.unlock();
    }
  }

  @NotNull
  private Condition getClassGlobalLockCondition(Class<?> clazz) {
    innerLock.lock();
    try {
      getOrCreateClassGlobalLock(clazz);
      return clazzGlobalLocksConditions.get(clazz);
    } finally {
      innerLock.unlock();
    }
  }

  @Nullable
  private ReentrantLock getCurrentLock(ID entityId, Class<?> clazz) {
    innerLock.lock();
    try {
      var entityLockMap = entitiesLockMaps.getOrDefault(clazz, Map.of());
      return entityLockMap.get(entityId);
    } finally {
      innerLock.unlock();
    }
  }

  @NotNull
  private ReentrantLock getOrCreateLock(ID entityId, Class<?> clazz) {

    Map<ID, ReentrantLock> entityLockMap = entitiesLockMaps.computeIfAbsent(clazz, ignore -> new HashMap<>());
    ReentrantLock lock = entityLockMap.computeIfAbsent(entityId, ignore -> new ReentrantLock());

    bindThreadWithEntity(entityId, clazz);

    return lock;
  }

  private void unlockEntity(@NonNull ID entityId, Class<?> clazz) {

    innerLock.lock();

    ReentrantLock currentLock = getCurrentLock(entityId, clazz);

    if (currentLock != null) {
      // attempt to release the lock:
      // current thread is the owner and everything is ok
      // current thread is not the owner and IllegalArgumentException is raised
      currentLock.unlock();

      unbindThreadWithEntity(entityId, clazz);

      // success - reduce number of locked objects
      postUnlockActions(clazz);
    }

    innerLock.unlock();
  }

  @ThreadSafeIMHO
  private void bindThreadWithEntity(ID entityId, Class<?> clazz) {
    innerLock.lock();
    try {
      long threadId = Thread.currentThread().getId();
      Map<Class<?>, Set<ID>> classIDMap = threadLockedEntities.computeIfAbsent(threadId, ignore -> new HashMap<>());
      Set<ID> threadClassEntities = classIDMap.getOrDefault(clazz, new HashSet<>());
      threadClassEntities.add(entityId);
      clazzNumberOfLockedObjects.computeIfAbsent(clazz, ignore -> new AtomicInteger(0));
    } finally {
      innerLock.unlock();
    }
  }

  @ThreadSafeIMHO
  private void unbindThreadWithEntity(ID entityId, Class<?> clazz) {
    innerLock.lock();
    try {
      long threadId = Thread.currentThread().getId();
      Map<Class<?>, Set<ID>> classIDMap = threadLockedEntities.getOrDefault(threadId, Map.of());
      Set<ID> threadClassEntities = classIDMap.getOrDefault(clazz, new HashSet<>());
      threadClassEntities.remove(entityId);
    } finally {
      innerLock.unlock();
    }
  }

  @ThreadSafeIMHO
  private int getNumberOfLockedByThreadEntities(Class<?> clazz) {
    innerLock.lock();
    try {
      long threadId = Thread.currentThread().getId();
      Map<Class<?>, Set<ID>> classIDMap = threadLockedEntities.getOrDefault(threadId, Map.of());
      return classIDMap.getOrDefault(clazz, Set.of()).size();
    } finally {
      innerLock.unlock();
    }
  }

  @NotNull
  @ThreadSafeIMHO
  private Set<ID> getTreadLockedEntities(Class<?> clazz) {
    innerLock.lock();
    try {
      long threadId = Thread.currentThread().getId();
      Map<Class<?>, Set<ID>> classIDMap = threadLockedEntities.getOrDefault(threadId, Map.of());
      return classIDMap.getOrDefault(clazz, Set.of());
    } finally {
      innerLock.unlock();
    }
  }

  @ThreadSafeIMHO
  private void unlockLockedByThread(Class<?> clazz) {
    innerLock.lock();
    try {
      Set<ID> lockedEntities = getTreadLockedEntities(clazz);
      Map<ID, ReentrantLock> lockMap = entitiesLockMaps.getOrDefault(clazz, Map.of());
      lockMap.forEach((id, reentrantLock) -> {
        if (lockedEntities.contains(id)) {
          reentrantLock.unlock();
        }
      });
    } finally {
      innerLock.unlock();
    }
  }

  @ThreadSafeIMHO
  protected int getNumberOfLockedObject(Class<?> clazz) {
    return getNumberOfBlockedObjects(clazz).get();
  }
}
