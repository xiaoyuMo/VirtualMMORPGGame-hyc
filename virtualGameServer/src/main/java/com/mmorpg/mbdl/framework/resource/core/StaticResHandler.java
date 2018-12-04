package com.mmorpg.mbdl.framework.resource.core;

import com.google.common.base.Preconditions;
import com.mmorpg.mbdl.EnhanceStarter;
import com.mmorpg.mbdl.framework.common.utils.SpringPropertiesUtil;
import com.mmorpg.mbdl.framework.resource.annotation.Id;
import com.mmorpg.mbdl.framework.resource.annotation.ResDef;
import com.mmorpg.mbdl.framework.resource.exposed.AbstractBeanFactoryAwareResResolver;
import com.mmorpg.mbdl.framework.resource.exposed.IResResolver;
import com.mmorpg.mbdl.framework.resource.exposed.IStaticRes;
import com.mmorpg.mbdl.framework.resource.impl.StaticRes;
import com.mmorpg.mbdl.framework.storage.annotation.ByteBuddyGenerated;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.SystemPropertyUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.withAnnotation;
import static org.springframework.util.ClassUtils.convertClassNameToResourcePath;

/**
 * 静态资源处理器
 * @author Sando Geek
 * @since v1.0
 **/
public class StaticResHandler implements BeanFactoryPostProcessor {
    private static Logger logger = LoggerFactory.getLogger(StaticResHandler.class);
    private String packageToScan;
    private String suffix;
    // 完整文件名 = 文件名加后缀名 -> StaticResDefinition
    private Map<String,StaticResDefinition> fileFullName2StaticResDefinition;
    // /** 资源类clazz -> 对应的baseClass的子类 */
    // Map<Class,Class> resDefClazz2IStaticResSubClazz;

    public void setPackageToScan(String packageToScan) {
        Preconditions.checkArgument(packageToScan!=null,"静态资源未配置包扫描路径");
        this.packageToScan = packageToScan;
    }

    public void setSuffix(String suffix) {
        Preconditions.checkArgument(suffix!=null,"静态资源未配置默认静态资源后缀");
        this.suffix = suffix;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        AbstractBeanFactoryAwareResResolver.setBeanFactory(beanFactory);
        /** 使SpringPropertiesUtil可用于{@link EnhanceStarter}中 */
        beanFactory.getBean(SpringPropertiesUtil.class);
        EnhanceStarter.setBeanFactory(beanFactory);
        EnhanceStarter.init();
        Map<Class, StaticResDefinition> class2StaticResDefinitionMap = getResDefClasses(packageToScan);
        init(class2StaticResDefinitionMap,beanFactory);
        handleStaticRes(beanFactory);
    }

    /**
     * 处理静态资源
     * @param beanFactory bean工厂
     */
    private void handleStaticRes(ConfigurableListableBeanFactory beanFactory){
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        beanFactory.getBeansOfType(IResResolver.class).forEach((key, value) -> {
            Resource[] resources;
            ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
            try {
                resources = resourcePatternResolver.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
                        "**/*" + value.suffix());
            } catch (IOException e) {
                String message = String.format("获取%s资源发生IO异常", value.suffix());
                logger.error(message);
                throw new RuntimeException(message);
            }
            Map<String, StaticResDefinition> fileName2StaticResDefinition = beanFactory
                    .getBean(StaticResDefinitionFactory.class).getFullFileNameStaticResDefinition();
            // 利用ForkJoinPool并行处理，因为包含IO,所以使用自定义的ForkJoinPool
            Runnable resourceLoadTask = () -> {
                Arrays.stream(resources).parallel().filter(Resource::isReadable).map((res)->{
                    String filename = res.getFilename();
                    // 初始化StaticResDefinition的List<Resource> resources字段
                    StaticResDefinition staticResDefinitionResult = Optional.ofNullable(fileName2StaticResDefinition.get(filename)).orElseGet(() -> {
                        String resPathRelative2ClassPath = IStaticResUtil.getResPathRelative2ClassPath((FileSystemResource) res);
                        return fileName2StaticResDefinition.get(resPathRelative2ClassPath);
                    });
                    if (staticResDefinitionResult!=null){
                        Resource resource = staticResDefinitionResult.getResource();
                        if ( resource != null ){
                            String newPath = IStaticResUtil.getResPathRelative2ClassPath((FileSystemResource) res);
                            String oldPath = IStaticResUtil.getResPathRelative2ClassPath((FileSystemResource) resource);
                            String message = String.format(
                                    "资源类[%s]对应两份文件：[%s],[%s],请在其注解上使用@ResDef(relativePath = \"%s\")或@ResDef(relativePath = \"%s\")确定此类对应的资源文件",
                                    staticResDefinitionResult.getvClass().getSimpleName(),
                                    newPath, oldPath, newPath, oldPath
                            );
                            throw new RuntimeException(message);
                        }
                        staticResDefinitionResult.setResource(res);
                    }
                    return staticResDefinitionResult;
                }).filter(Objects::nonNull).forEach((staticResDefinition -> {
                    logger.debug("静态资源{}成功关联到类[{}]",staticResDefinition.getFullFileName(),staticResDefinition.getvClass().getSimpleName());
                    value.resolve(staticResDefinition);
                }));
            };


            // 使用默认ForkJoinPool执行耗时测试
            // resourceLoadTask.run();

            final ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
                final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                worker.setName("资源加载线程-" + worker.getPoolIndex());
                return worker;
            };

            String threadSize = SpringPropertiesUtil.getProperty("sever.config.static.res.load.thread.size");
            ForkJoinPool forkJoinPool = new ForkJoinPool(Integer.parseInt(threadSize), factory, null, false);
            try {
                forkJoinPool.submit(resourceLoadTask).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("静态资源解析失败",e);
            }
            forkJoinPool.shutdown();
        });
        stopWatch.stop();
        logger.info("静态资源解析完毕，耗时{}ms",stopWatch.getTime());
    }

    /**
     * 初始化
     *
     * <p>初始化StaticResDefinitionFactory,每个表格型资源类生成一个对应的IStaticRes的子类,并存放到StaticResDefinition</p>
     * @param class2StaticResDefinitionMap
     * @return 资源类clazz -> 对应的baseClass的子类,如果集合大小为0，返回null
     */
    @SuppressWarnings("unchecked")
    private void init(Map<Class,StaticResDefinition> class2StaticResDefinitionMap, ConfigurableListableBeanFactory beanFactory){
        if (class2StaticResDefinitionMap.size() == 0) {
            return;
        }
        class2StaticResDefinitionMap.keySet().forEach((clazz)->{
            ResDef resDef = (ResDef) clazz.getAnnotation(ResDef.class);
            StaticResDefinition staticResDefinition = class2StaticResDefinitionMap.get(clazz);

            /** 表格型资源，检查其Id的唯一性，并生成{@link StaticRes}的子类实例 */
            if (resDef.isTable()){
                Set<Field> fields = getAllFields(clazz, withAnnotation(Id.class));
                if (fields.size() > 1){
                    throw new RuntimeException(String.format("表格型资源类[%s]包含多个@Id注解的字段",clazz.getSimpleName()));
                }else if (fields.size() < 1){
                    throw new RuntimeException(String.format("表格型资源类[%s]不包含@Id注解的字段，如非表格型资源，请在其@ResDef中把isTable设置为false",clazz.getSimpleName()));
                }
                Field idField = fields.toArray(new Field[0])[0];
                staticResDefinition.setIdField(idField);
                Class idBoxedType = null;
                if (idField.getType().isPrimitive()){
                    switch (idField.getType().getSimpleName()) {
                        case ("int") : {
                            idBoxedType = Integer.class;
                            break;
                        }
                        case ("boolean") : {
                            idBoxedType = Boolean.class;
                            break;
                        }
                        case ("short") : {
                            idBoxedType = Short.class;
                            break;
                        }
                        case ("byte") : {
                            idBoxedType = Byte.class;
                            break;
                        }
                        case ("char") : {
                            idBoxedType = Character.class;
                            break;
                        }
                        case ("long") : {
                            idBoxedType = Long.class;
                            break;
                        }
                        case ("float") : {
                            idBoxedType = Float.class;
                            break;
                        }
                        case ("double") : {
                            idBoxedType = Double.class;
                            break;
                        }
                        default:
                    }
                }
                if (idBoxedType == null){
                    idBoxedType = idField.getType();
                }

                TypeDescription.Generic genericSuperClass =
                        TypeDescription.Generic.Builder.parameterizedType(StaticRes.class, idBoxedType, clazz).build();
                String packageName = clazz.getPackage().getName();
                Class<?> subClass = new ByteBuddy()
                        .subclass(genericSuperClass)
                        .name(packageName + "." + StaticRes.class.getSimpleName() + idBoxedType.getSimpleName() + clazz.getSimpleName())
                        .annotateType(AnnotationDescription.Builder.ofType(ByteBuddyGenerated.class).build())
                        .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.INJECTION).getLoaded();
                staticResDefinition.setvClass(clazz);
                try {
                    StaticRes instance = (StaticRes)subClass.newInstance();
                    instance.setFullFileName(staticResDefinition.getFullFileName());
                    staticResDefinition.setStaticRes(instance);
                } catch (InstantiationException | IllegalAccessException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("[%s]<%s,%s>类型的bean实例化失败",IStaticRes.class,idBoxedType,clazz.getSimpleName()));
                }
            }
            StaticResDefinitionFactory staticResDefinitionFactory = beanFactory.getBean(StaticResDefinitionFactory.class);
            Map<String, StaticResDefinition> fullFileNameStaticResDefinition = staticResDefinitionFactory.getFullFileNameStaticResDefinition();
            String fullFileName = staticResDefinition.getFullFileName();
            if (fullFileNameStaticResDefinition.keySet().contains(fullFileName)){
                Class<?> oldClass = fullFileNameStaticResDefinition.get(fullFileName).getvClass();
                throw new RuntimeException(String.format("类[%s]与类[%s]对应同一个资源文件名[%s]",clazz,oldClass,fullFileName));
            }
            fullFileNameStaticResDefinition.put(fullFileName,staticResDefinition);
        });
    }

    /**
     * 根据包名获取被ResDef注解的所有class对象
     * @param packageName
     * @return Map<Class,StaticResDefinition>
     * @throws IOException
     */
    private Map<Class,StaticResDefinition> getResDefClasses(String packageName) {
        ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
        Map<Class,StaticResDefinition> result = new HashMap<>(64);
        String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + resolveBasePackage(packageName)
                + "/" + "**/*.class";
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(packageSearchPath);
        } catch (IOException e) {
            throw new RuntimeException(String.format("无法获取指定包下[%s]的.class资源",packageName));
        }
        Class clz;
        // TODO 实现MetadataReaderPostProcessor,避免多次循环.class文件Resource，
        // 思路：第一次遍历找出所有实现了MetadataReaderPostProcessor的.class文件，实例化后在第二次遍历时，调用其postProcess方法
        for (Resource resource : resources) {
            if (resource.isReadable()) {
                MetadataReader metadataReader = null;
                try {
                    metadataReader = metadataReaderFactory.getMetadataReader(resource);
                } catch (IOException e) {
                    throw new RuntimeException(String.format("无法读取[%s]的Metadata",resource.getFilename()));
                }
                AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();
                if (!annotationMetadata.hasAnnotation(ResDef.class.getName())) {
                    continue;
                }
                try {
                    Class<?> resClazz = Class.forName(metadataReader.getClassMetadata().getClassName());
                    result.put(resClazz,new StaticResDefinition());
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(String.format("无法加载类[%s]",metadataReader.getClassMetadata().getClassName()));
                }

            }
        }
        return result;
    }
    protected String resolveBasePackage(String basePackage) {
        return convertClassNameToResourcePath(SystemPropertyUtils.resolvePlaceholders(basePackage));
    }

}