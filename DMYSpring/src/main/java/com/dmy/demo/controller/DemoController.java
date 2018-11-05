package com.dmy.demo.controller;

import com.dmy.demo.entry.User;
import com.dmy.demo.service.IDemoService;
import com.dmy.framework.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@DMYController
@DMYRequestMapping("/demo")
public class DemoController {

    @DMYAutowired//("demoService") 括号内的内容取决于注入的bean指定注入名，没有指定则可以不写
    private IDemoService demoService ;

    @DMYAutowired
    private User user ;

    @DMYRequestMapping("/query")
    public void query(HttpServletResponse response, @DMYRequestParam("name")String name){
        String result = demoService.get(name);
        user.setName(name);
        System.out.println("你好");
        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
