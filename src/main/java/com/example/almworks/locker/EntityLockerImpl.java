package com.example.almworks.locker;

import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class EntityLockerImpl<ID> implements EntityLocker<ID> {

  private final ReentrantLock globalLock;
  private final Condition globalLockCondition;
  private final Map<Class<?>, Map<ID, ReentrantLock>> entitiesLockMaps;
  private final AtomicInteger numberOfLockedObjects = new AtomicInteger(0);

  public EntityLockerImpl() {
    this.globalLock = new ReentrantLock();
    this.globalLockCondition = globalLock.newCondition();
    this.entitiesLockMaps = new HashMap<>();
  }

  @Override
  public boolean globalLock() throws InterruptedException {

    // block everything
    globalLock.lockInterruptibly();

    // then it's not possible to get global lock
    while (numberOfLockedObjects.get() != 0) {
      // release until no other thread lock
      globalLockCondition.await();
    }

    return true;
  }

  @Override
  public void globalUnlock() {
    globalLock.unlock();
  }

  @Override
  public boolean lock(@NonNull ID entityId, Class<?> clazz) throws InterruptedException {

    globalLock.lock();

    try {
      ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

      entityLock.lockInterruptibly();

      postLockActions();
      return true;
    } finally {
      globalLock.unlock();
    }
  }

  @Override
  public boolean lock(ID entityId, Class<?> clazz, long timeout, TimeUnit timeUnit) throws InterruptedException {

    long startTimeInBaseUnit = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);

    if (!globalLock.tryLock(timeout, timeUnit)) {
      return false;
    }

    // under global lock
    try {
      ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

      long endTimeInBaseUnit = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
      long lastTimeout = startTimeInBaseUnit + timeout - endTimeInBaseUnit;

      if (!entityLock.tryLock(lastTimeout, timeUnit)) {
        return false;
      }

      postLockActions();
      return true;
    } finally {
      globalLock.unlock();
    }
  }

  @Override
  public void unlock(@NonNull ID entityId, Class<?> clazz) {

    ReentrantLock currentLock = getCurrentLock(entityId, clazz);

    if (currentLock != null) {
      // attempt to release the lock:
      // current thread is the owner and everything is ok
      // current thread is not the owner and IllegalArgumentException is raised
      currentLock.unlock();
      // success - reduce number of locked objects
      postUnlockActions();
    }
  }

  @ThreadSafeIMHO
  private void postLockActions() {
    numberOfLockedObjects.incrementAndGet();
  }

  @ThreadSafeIMHO
  private void postUnlockActions() {
    globalLock.lock();
    try {
      numberOfLockedObjects.decrementAndGet();
      globalLockCondition.signalAll();
    } finally {
      globalLock.unlock();
    }
  }

  private @Nullable ReentrantLock getCurrentLock(ID entityId, Class<?> clazz) {
    var entityLockMap = entitiesLockMaps.getOrDefault(clazz, Map.of());
    return entityLockMap.get(entityId);
  }

  private @NotNull ReentrantLock getOrCreateLock(ID entityId, Class<?> clazz) {
    Map<ID, ReentrantLock> entityLockMap = entitiesLockMaps.computeIfAbsent(clazz, ignore -> new HashMap<>());
    return entityLockMap.computeIfAbsent(entityId, ignore -> new ReentrantLock());
  }

  @ThreadSafeIMHO
  protected int getNumberOfLockerObject() {
    return numberOfLockedObjects.get();
  }
}
