package com.dmy.demo.service;

import com.dmy.framework.annotation.DMYService;

@DMYService//("demoService")
public class DemoService implements IDemoService {

    public String get(String name) {
        return "This is "+name;
    }
}
