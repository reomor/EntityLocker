package com.reomor.locker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Slf4j
class GlobalLockEntityLockerTest extends AbstractEntityLockerTest{

  @Test
  @Timeout(value = 3)
  void globalLockReentrancy() throws InterruptedException {

    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>();

    EntityLockerImpl<String> jailbreak = entityLocker.jailbreak();

    boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
    assertTrue(lockResult);

    boolean lockResultRepeat = entityLocker.globalLock(TEST_ENTITY_CLASS);
    assertTrue(lockResultRepeat);

    assertEquals(2, jailbreak.getCurrentClassGlobalLock(TEST_ENTITY_CLASS).getHoldCount());
    entityLocker.globalUnlock(TEST_ENTITY_CLASS);

    assertEquals(1, jailbreak.getCurrentClassGlobalLock(TEST_ENTITY_CLASS).getHoldCount());
    entityLocker.globalUnlock(TEST_ENTITY_CLASS);

    assertEquals(0, jailbreak.getCurrentClassGlobalLock(TEST_ENTITY_CLASS).getHoldCount());
  }

  @Test
  @Timeout(value = 3)
  void whenGlobalLock_successLockAttempt_afterGlobalUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        completeLatch.countDown();

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  @Timeout(value = 3)
  void whenGlobalLock_failTimeoutLockAttempt() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertFalse(lockResult);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  @Timeout(value = 3)
  void whenLock_successGlobalLockAttempt_afterUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  @Timeout(value = 3)
  void whenGlobalLock_LockIsPossibleAfterGlobalUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch phase2 = new CountDownLatch(1);
    CountDownLatch phase3 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        phase1.countDown();
        phase2.await();

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);
        phase3.countDown();

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertFalse(lockResult);

        phase2.countDown();
        phase3.await();

        lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  void whenGlobalLock_waitingEntityUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    final int globalLockWaiting = 1000;

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        phase1.countDown();

        // after 1 seconds entity unlocks
        Thread.sleep(globalLockWaiting);

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        long startTime = System.currentTimeMillis();
        // that should cause infinite waiting
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);

        long endTime = System.currentTimeMillis();

        // waiting is approximately [990,1010] milliseconds
        assertTrue(Math.abs(endTime - startTime - globalLockWaiting) <= 30);

        // successfully locked
        assertTrue(lockResult);

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  void whenGlobalLockTimeout_waitingEntityUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    final int globalLockWaiting = 1000;

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        phase1.countDown();

        // after 1 seconds entity unlocks
        Thread.sleep(globalLockWaiting);

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        long startTime = System.currentTimeMillis();
        // that should cause infinite waiting
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);

        long endTime = System.currentTimeMillis();

        // waiting is approximately [990,1010] milliseconds
        assertTrue(Math.abs(endTime - startTime - globalLockWaiting) <= 30);

        // successfully locked
        assertTrue(lockResult);

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  @Timeout(value = 3)
  void globalLock_removeAfterUnlock() throws InterruptedException {

    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>();

    boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
    assertTrue(lockResult);

    entityLocker.globalUnlock(TEST_ENTITY_CLASS);

    assertNull(entityLocker.jailbreak().clazzNumberOfLockedObjects.get(TEST_ENTITY_CLASS));
    assertNull(entityLocker.jailbreak().clazzGlobalLocksConditions.get(TEST_ENTITY_CLASS));
    assertNull(entityLocker.jailbreak().clazzGlobalLocks.get(TEST_ENTITY_CLASS));
  }

  @Test
  @Timeout(value = 3)
  void globalLock_noCleanupAfterUnlock_whenReentrant() throws InterruptedException {

    EntityLockerImpl<String> entityLocker = spy(new EntityLockerImpl<>());

    boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
    assertTrue(lockResult);

    boolean lockResultRepeat = entityLocker.globalLock(TEST_ENTITY_CLASS);
    assertTrue(lockResultRepeat);

    entityLocker.globalUnlock(TEST_ENTITY_CLASS);

    assertNotNull(entityLocker.jailbreak().clazzNumberOfLockedObjects.get(TEST_ENTITY_CLASS));
    assertNotNull(entityLocker.jailbreak().clazzGlobalLocksConditions.get(TEST_ENTITY_CLASS));
    assertNotNull(entityLocker.jailbreak().clazzGlobalLocks.get(TEST_ENTITY_CLASS));

    // only after one unlock
    verify(entityLocker, never()).clearClassGlobalLock(TEST_ENTITY_CLASS);
  }

  @Test
  @Timeout(value = 3)
  void globalLockTimeout_waitingEntityUnlock_NoCleanUp() throws InterruptedException {

    EntityLockerImpl<String> entityLocker = spy(new EntityLockerImpl<>());

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        phase1.countDown();
        Thread.sleep(250);

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        // blocked until unlocked in another thread
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);

        // successfully locked
        assertTrue(lockResult);

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();

    // only after one unlock
    verify(entityLocker, times(1)).clearClassGlobalLock(TEST_ENTITY_CLASS);
  }

  @Data
  @AllArgsConstructor
  private static class Entity<ID> {
    private ID entityId;
    private int value;
  }
}
