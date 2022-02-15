package com.example.almworks.locker;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class EntityLockerImpl<ID> implements EntityLocker<ID> {

  private final Map<ID, ReentrantLock> entityLockMap;

  public EntityLockerImpl() {
    this.entityLockMap = new ConcurrentHashMap<>();
  }

  @Override
  public boolean lock(ID entityId) throws InterruptedException {

    checkId(entityId);

    ReentrantLock currentLock = getOrCreateLock(entityId);
    currentLock.lockInterruptibly();

    return true;
  }

  @Override
  public void unlock(ID entityId) {

    checkId(entityId);

    ReentrantLock currentLock = getCurrentLock(entityId);

    if (currentLock != null) {
      // attempt to release the lock:
      // current thread is the owner and everything is ok
      // current thread is not the owner and IllegalArgumentException is raised
      currentLock.unlock();
    }
  }

  private void checkId(ID entityId) {
    Objects.requireNonNull(entityId, "entity identifier could not be null");
  }

  private ReentrantLock getCurrentLock(ID entityId) {
    return entityLockMap.get(entityId);
  }

  private ReentrantLock getOrCreateLock(ID entityId) {
    return entityLockMap.computeIfAbsent(entityId, ignore -> new ReentrantLock());
  }
}
