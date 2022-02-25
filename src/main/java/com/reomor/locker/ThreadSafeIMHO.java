package com.reomor.locker;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ThreadSafeIMHO {
}
