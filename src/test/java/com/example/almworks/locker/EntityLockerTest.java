package com.example.almworks.locker;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
class EntityLockerTest {

  private static final String TEST_ID = "TEST_ID";

  @Test
  void lockUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    boolean lockResult = entityLocker.lock(TEST_ID);
    assertTrue(lockResult);

    entityLocker.unlock(TEST_ID);
  }
}