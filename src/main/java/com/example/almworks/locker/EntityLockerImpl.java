package com.example.almworks.locker;

import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class EntityLockerImpl<ID> implements EntityLocker<ID> {

  private final Object innerLock;
  private final Map<Class<?>, Map<ID, ReentrantLock>> entitiesLockMaps;

  public EntityLockerImpl() {
    this.innerLock = new Object();
    this.entitiesLockMaps = new HashMap<>();
  }

  @Override
  public boolean lock(@NonNull ID entityId, Class<?> clazz) throws InterruptedException {

    ReentrantLock currentLock = getOrCreateLock(entityId, clazz);

    currentLock.lockInterruptibly();

    return true;
  }

  @Override
  public boolean lock(ID entityId, Class<?> clazz, long timeout, TimeUnit timeUnit) throws InterruptedException {

    ReentrantLock currentLock = getOrCreateLock(entityId, clazz);

    if (!currentLock.tryLock(timeout, timeUnit)) {
      return false;
    }

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
    }
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
