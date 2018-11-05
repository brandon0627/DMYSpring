package com.dmy.framework.servlet;

import com.dmy.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

public class DMYDispatcherServlet extends HttpServlet {

    // 跟web.xml里的param-name 一致
    private static final String LOCATION = "contextConfigLocation";

    // 保存所有配置信息
    private Properties properties = new Properties();

    // 保存所有被扫描到的相关的类名
    private List<String> classNames = new ArrayList<String>();

    // 核心IOC容器， 保存所有初始化的Bean
    private Map<String, Object> ioc = new HashMap<String, Object>();

    // 保存所有的URL和方法的映射关系
    private Map<String,Method> handlerMapping = new HashMap<String, Method>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            this.doDispatch(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
            //如果出现异常则把异常信息丢给页面
            resp.getWriter().write("500 Exception,Details:\r\n"+Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        if (this.handlerMapping.isEmpty()) return;

        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath,"").replaceAll("/+","/");

        //如果该url匹配不到handler,则 404
        if (!handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 Not Found!!!");
            return;
        }

        Method method = handlerMapping.get(url);
        //获取方法参数列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        //获取请求的参数
        Map<String, String[]> parameterMap = req.getParameterMap();
        //保存参数值
        Object[] paramValues = new Object[parameterTypes.length];
        //方法参数列表
        for (int i = 0; i < parameterTypes.length; i++){
            Class parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class){
                paramValues[i] = req;
                continue;
            }else if (parameterType == HttpServletResponse.class){
                paramValues[i] = resp;
                continue;
            }else if (parameterType == String.class){
                for (Map.Entry<String, String[]> param : parameterMap.entrySet()){
                    String value = Arrays.toString(param.getValue())
                                    .replaceAll("\\[|\\]","")   //去掉 [ ]
                                    .replaceAll(",\\s",",");    //去掉空格 \s
                    paramValues[i] = value;
                }
            }
        }

        String beanName = lowerFirstCase(method.getDeclaringClass().getSimpleName());
        method.invoke(this.ioc.get(beanName),paramValues);

    }

    @Override
    public void init(ServletConfig config) throws ServletException {

        //1.加载所有配置文件
        doLoadConfig(config.getInitParameter(LOCATION));

        //2.扫描所有相关的类
        doScanner(properties.getProperty("scanPackage"));

        //3.初始化所有相关类的实例，并保存到IOC容器中
        doInstance();

        //4.依赖注入
        doAutowired();

        //5.构造handlerMapping
        initHandlerMapping();

        //6.初始化完毕，输出提示; 等待Get/Post方法执行
        System.out.println("DMY framework init is completed!");
    }

    private void doLoadConfig(String location) {
        InputStream is = null ;
        try {
            is = this.getClass().getClassLoader().getResourceAsStream(location);
            properties.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            try {
                if(null != is){
                    is.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void doScanner(String packageName) {
        //将包路径转换为文件路径
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        File dir = new File(url.getFile());
        for (File file : dir.listFiles()){
            //如果是文件夹，则继续递归
            if (file.isDirectory()){
                doScanner(packageName + "." +file.getName());
            }else{
                classNames.add(packageName + "." +file.getName() .replace(".class","").trim());
            }
        }
    }

    private void doInstance() {
        //size()是取出列表大小  isEmpty是先取出size 再判断是否等于0
        if (classNames.size() == 0) return;
        try {
            for (String className : classNames){
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(DMYController.class)){    //Controller注入
                    String beanName = lowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, clazz.newInstance());
                }else if (clazz.isAnnotationPresent(DMYService.class)) {    //Service注入
                    DMYService service = clazz.getAnnotation(DMYService.class);
                    String beanName = service.value();
                    //如果用户设置了service的名字就取用户自己设置的
                    if (!"".equals(beanName)) {
                        ioc.put(beanName, clazz.newInstance());
                        continue;
                    }
                    //如果用户没有设置，就按接口类型创建一个实例
                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> i : interfaces) {
                        ioc.put(i.getName(), clazz.newInstance());
                    }
                }else if (clazz.isAnnotationPresent(DMYComponent.class)){   //Component组件注入
                    DMYComponent component = clazz.getAnnotation(DMYComponent.class);
                    String beanName = component.value();
                    //如果用户没有设置component的名字就取默认配置
                    if ("".equals(beanName)) {
                        //这里我们取的是Class对象的“实体”名称（包含包名）
                        beanName = clazz.getName();
                    }
                    ioc.put(beanName, clazz.newInstance());
                }else{
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()) return;

        for (Map.Entry<String, Object> entry : ioc.entrySet()){
            //获取实例对象的所有属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields){
                //如果属性值没有自动注入注解则跳过
                if (!field.isAnnotationPresent(DMYAutowired.class)) continue;

                DMYAutowired autowired = field.getAnnotation(DMYAutowired.class);
                String beanName = autowired.value().trim();
                //如果没有设置注入名
                if ("".equals(beanName)){
                    beanName = field.getType().getName();
                }
                //设置私有私有属性的访问权限
                field.setAccessible(true);
                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                    System.out.println("autowired: "+ entry.getValue()+ "-->" + ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }

    private void initHandlerMapping() {
        if (ioc.isEmpty()) return;

        for (Map.Entry<String, Object> entry : ioc.entrySet()){
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(DMYController.class)) continue;

            String baseUrl = "";
            //获取Controller中的url
            if(clazz.isAnnotationPresent(DMYRequestMapping.class)){
                DMYRequestMapping requestMapping = clazz.getAnnotation(DMYRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //获取method的配置的url
            Method[] methods = clazz.getMethods();
            for (Method method : methods){
                //没有加RequestMapping的方法直接跳过
                if (!method.isAnnotationPresent(DMYRequestMapping.class)) continue;

                DMYRequestMapping requestMapping = method.getAnnotation(DMYRequestMapping.class);
                String url = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+","/");

                handlerMapping.put(url, method);
                System.out.println("mapped url=" + url + ", method=" +method);
            }
        }
    }

    /** 
    * @Description: 首字母小写
     *                 转出char数组 char大小写相差32  A + 32 = a
    * @Param: [str] 
    * @return: java.lang.String 
    * @Author: Brandon
    * @Date: 2018/11/2 
    */ 
    private String lowerFirstCase(String str){
        char[] chars = str.toCharArray();
        chars[0] += 32 ;
        /***这里是自己基础不好踩过的坑
            这里转换为String 用的是类型转换的valueOf；
            千万别toString，toString返回的是个对象，导致IOC容器里put进去的beanName是个对象地址
            反射的时候就会找不到bean
        **********/
        return String.valueOf(chars);
    }


}
