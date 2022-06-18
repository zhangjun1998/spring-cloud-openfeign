**简介**

这里是 [『香蕉大魔王』](https://github.com/zhangjun1998) 的 spring-cloud-openfeign 源码解析，[原始项目地址](https://github.com/zhangjun1998/spring-cloud-openfeign) 在这。
技术暂时还比较拙劣，可能有很多理解不到位或有出入的地方，所以源码注释仅供参考哈，如有错误还请指正，错误很多的话就别说了，请把一些优秀的博客地址贴给我，我爱学习。

目前还没时间总结整体流程，有时间了我再用图文结合的方式把核心流程描述一遍，现在先 mark 一下。

忘记说了，这是 tag 为 v3.1.1 的代码，因为 master 分支代码我下载不了 spring-cloud 还没发布到 maven 的依赖。
然后其中涉及到的一些关于 openfeign 的源码需要移步到 [香蕉大魔王的openfeign源码解析](https://github.com/zhangjun1998/feign) 去看，毕竟 spring-cloud-openfeign 只是对它的一个封装。

**已看和待看的代码如下：**

+ [x] @EnableFeignClients：OpenFeign的启动注解
+ [x] FeignAutoConfiguration：自动注入一些Bean/配置属性，方便封装成 starter 开箱即用
+ [x] FeignClientsRegistrar：注入被@FeignClient修饰的接口代理对象到容器中
+ [x] FeignContext：用于在创建 FeignClient 的过程中提供所需的类实例/资源
+ [x] FeignClientFactoryBean：用于创建 feignClient 的 factoryBen
+ [x] Targeter：用于控制生成何种 feignClient 以及提供实现细节
+ [x] DefaultTargeter：生成默认的代理对象，不支持熔断降级
+ [x] FeignCircuitBreakerTargeter：用于生成支持熔断器的代理对象
+ [x] FeignCircuitBreaker：对熔断器配置的包装，包含了熔断器工厂等引用，可以基于此创建熔断器
  + [x] FeignCircuitBreaker.Builder：继承自 Feign.Builder 
+ [x] FeignCircuitBreakerInvocationHandler：生成的代理对象关联的 InvocationHandler，调用对象的任意方法都会被转发到该类进行处理
+ [ ] Encoder 的实现：spring-cloud-openfeign 对 openfeign 中 Encoder 接口的实现，用于HTTP请求编码
  + [ ] SpringEncoder
  + [ ] SpringPojoFormEncoder
  + [ ] PageableSpringEncoder
+ [ ] Decoder 的实现：spring-cloud-openfeign 对 openfeign 中 Decoder 接口的实现，用于HTTP响应解码
  + [ ] SpringDecoder
  + [ ] ResponseEntityDecoder
+ [ ] Contract：spring-cloud-openfeign 对 openfeign 中 Contract 接口的实现，用于适配 Spring MVC 注解，解析出MVC注解表示的数据
  + [ ] SpringMvcContract
+ [ ] ...
+ 

**涉及到openfeign的部分代码如下：**
+ [x] Feign：Feign 对象的抽象定义，定义了创建 feignClient 对象相关的一些基本操作
  + [x] Feign.Builder：内部类，Feign 对象的构造器
+ [ ] ReflectiveFeign：Feign 的子类，可以使用反射创建出接口的代理对象(feignClient)
+ [ ] InvocationHandlerFactory：InvocationHandler 的工厂类，用于创建 InvocationHandler，对接口代理对象的方法调用进行拦截，以此实现方法增强
+ [ ] ...

**联系方式：**

+ 邮箱：zhangjun_java@163.com
+ 微信：rzy-zj

如要联系我请备注来意，不知道怎么备注的我提供个模板：「oh，你的 spring-cloud-openfeign 源码解析的太好了，加个好友瞧一瞧」。好，祝大家技术进步，生活快乐。
