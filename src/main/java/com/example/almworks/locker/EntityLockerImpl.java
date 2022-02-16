package com.example.almworks.locker;

import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class EntityLockerImpl<ID> implements EntityLocker<ID> {

  // it's better to lock on definite object, than 'this' instance
  private final Object innerLock;
  private final ReentrantLock globalLock;
  private final Condition globalLockCondition;
  private final AtomicInteger numberOfLockedObjects = new AtomicInteger(0);
  private final Map<Class<?>, Map<ID, ReentrantLock>> entitiesLockMaps;

  public EntityLockerImpl() {
    this.innerLock = new Object();
    this.globalLock = new ReentrantLock();
    this.globalLockCondition = globalLock.newCondition();
    this.entitiesLockMaps = new HashMap<>();
  }

  @Override
  public boolean globalLock() throws InterruptedException {

    // it's not possible to get global lock
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

    ReentrantLock currentLock = getCurrentLock(entityId, clazz);

    synchronized (innerLock) {
      // no way to get a new lock when globally locked
      if (currentLock == null && globalLock.isLocked()) {
        return false;
      } else if (currentLock != null) {

        // allow possible reentrancy for owner
        if (!currentLock.isHeldByCurrentThread()) {
          return false;
        }

        // no way to reuse free lock
        if (!currentLock.isLocked() && globalLock.isLocked()) {
          return false;
        }
      }
    }

    ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

    entityLock.lockInterruptibly();

    postLockActions();
    return true;
  }

  @Override
  public boolean lock(ID entityId, Class<?> clazz, long timeout, TimeUnit timeUnit) throws InterruptedException {

    ReentrantLock entityLock = getOrCreateLock(entityId, clazz);

    if (!entityLock.tryLock(timeout, timeUnit)) {
      return false;
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

  private void postLockActions() {
    numberOfLockedObjects.incrementAndGet();
  }

  private void postUnlockActions() {
    numberOfLockedObjects.decrementAndGet();
  }

  private ReentrantLock getCurrentLock(ID entityId, Class<?> clazz) {
    synchronized (innerLock) {
      var entityLockMap = entitiesLockMaps.getOrDefault(clazz, Map.of());
      return entityLockMap.get(entityId);
    }
  }

  private ReentrantLock getOrCreateLock(ID entityId, Class<?> clazz) {
    synchronized (innerLock) {
      Map<ID, ReentrantLock> entityLockMap = entitiesLockMaps.computeIfAbsent(clazz, ignore -> new HashMap<>());
      return entityLockMap.computeIfAbsent(entityId, ignore -> new ReentrantLock());
    }
  }
}
