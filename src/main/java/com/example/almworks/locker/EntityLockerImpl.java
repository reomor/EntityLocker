package com.example.almworks.locker;

import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class EntityLockerImpl<ID> implements EntityLocker<ID> {

  private static final long NO_TIMEOUT_SIGN = Long.MIN_VALUE;

  // it's better to lock on definite object, than 'this' instance
  private final Object innerLock;
  private final ReentrantLock globalLock;
  private final Map<Class<?>, Map<ID, ReentrantLock>> entitiesLockMaps;
  private final AtomicInteger numberOfLockedObjects = new AtomicInteger(0);

  public EntityLockerImpl() {
    this.innerLock = new Object();
    this.globalLock = new ReentrantLock();
    this.entitiesLockMaps = new HashMap<>();
  }

  @Override
  public boolean globalLock() throws InterruptedException {

    // then it's not possible to get global lock
    if (numberOfLockedObjects.get() != 0) {
      return false;
    }

    globalLock.lockInterruptibly();

    return true;
  }

  @Override
  public void globalUnlock() {
    globalLock.unlock();
  }

  @Override
  public boolean lock(@NonNull ID entityId, Class<?> clazz) throws InterruptedException {
    // from that moment copying the code doesn't worth it
    return lock(entityId, clazz, NO_TIMEOUT_SIGN, TimeUnit.NANOSECONDS);
  }

  @Override
  public boolean lock(ID entityId, Class<?> clazz, long timeout, TimeUnit timeUnit) throws InterruptedException {

    ReentrantLock currentLock = getCurrentLock(entityId, clazz);

    synchronized (innerLock) {
      // no way to get a new lock when globally locked
      if (globalLock.isLocked() && currentLock == null) {
        return false;
      } else if (globalLock.isLocked() && currentLock != null) {

        // allow possible reentrancy for owner
        // no way to reuse free lock
        if (!currentLock.isHeldByCurrentThread() || !currentLock.isLocked()) {
          return false;
        }
      }
    }

    ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

    if (timeout == NO_TIMEOUT_SIGN) {
      entityLock.lockInterruptibly();
    } else {
      if (!entityLock.tryLock(timeout, timeUnit)) {
        return false;
      }
    }

    postLockActions();
    return true;
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
    numberOfLockedObjects.decrementAndGet();
  }

  @ThreadSafeIMHO
  private @Nullable ReentrantLock getCurrentLock(ID entityId, Class<?> clazz) {
    synchronized (innerLock) {
      var entityLockMap = entitiesLockMaps.getOrDefault(clazz, Map.of());
      return entityLockMap.get(entityId);
    }
  }

  @ThreadSafeIMHO
  private @NotNull ReentrantLock getOrCreateLock(ID entityId, Class<?> clazz) {
    synchronized (innerLock) {
      Map<ID, ReentrantLock> entityLockMap = entitiesLockMaps.computeIfAbsent(clazz, ignore -> new HashMap<>());
      return entityLockMap.computeIfAbsent(entityId, ignore -> new ReentrantLock());
    }
  }

  protected int getNumberOfLockerObject() {
    return numberOfLockedObjects.get();
  }
}
