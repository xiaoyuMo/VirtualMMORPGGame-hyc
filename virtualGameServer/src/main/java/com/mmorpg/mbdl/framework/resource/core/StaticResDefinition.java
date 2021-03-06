package com.mmorpg.mbdl.framework.resource.core;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.mmorpg.mbdl.framework.common.utils.FileUtils;
import com.mmorpg.mbdl.framework.common.utils.ProtostuffUtils;
import com.mmorpg.mbdl.framework.common.utils.StringUtil;
import com.mmorpg.mbdl.framework.resource.impl.StaticRes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.xerial.snappy.SnappyInputStream;
import org.xerial.snappy.SnappyOutputStream;

import java.io.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * 静态资源定义
 *
 * @author Sando Geek
 * @since v1.0
 **/
public class StaticResDefinition {
    private static final Logger logger = LoggerFactory.getLogger(StaticResDefinition.class);
    private static final String runParentPath = IStaticResUtil.getRunParentPath();
    /** 资源文件全路径名 */
    private String fullFileName;
    /**
     * 版本（最后修改时间）
     */
    private long[] version = new long[1];
    /** V的实际类型 */
    private Class<?> vClass;

    private Resource resource;

    /** id字段 */
    private Field idField;
    private Map<String,Field> uniqueFieldName2Field;
    private Map<String,Field> indexFieldName2Field;
    /**
     * 实际存储静态资源数据的对象
     */
    private StaticRes staticRes;
    private Field key2ResourceField;
    private ImmutableMap.Builder key2ResourceBuilder = ImmutableMap.builder();
    private ConfigurableListableBeanFactory beanFactory;

    private File tempFile;

    public StaticResDefinition(Class<?> vClass) {
        fullFileName = IStaticResUtil.getFullFileName(vClass);
        this.vClass = vClass;
        String filePath = StringUtil.fommat("{}/resTemp/{}.pbstuff", runParentPath, vClass.getSimpleName());
        tempFile = new File(filePath);
    }

    public String getFullFileName() {
        return fullFileName;
    }

    public Field getIdField() {
        return idField;
    }

    public void setIdField(Field idField) {
        this.idField = idField;
        idField.setAccessible(true);
    }

    public Class<?> getvClass() {
        return vClass;
    }

    public void setStaticRes(StaticRes staticRes) {
        this.staticRes = staticRes;
        try {
            key2ResourceField = this.staticRes.getClass().getSuperclass().getDeclaredField("key2Resource");
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        key2ResourceField.setAccessible(true);
    }

    public void setBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public ConfigurableListableBeanFactory getBeanFactory() {
        return beanFactory;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
        try {
            this.version[0] = resource.getFile().lastModified();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ImmutableMap registerToBeanFactory() throws IllegalAccessException {
        String resBeanName = StringUtils.uncapitalize(staticRes.getClass().getSimpleName());
        beanFactory.registerSingleton(resBeanName, staticRes);
        return (ImmutableMap) key2ResourceField.get(staticRes);
    }

    /**
     * 设置{@link StaticRes}的存储map，并返回该map
     * @return
     */
    public void setImmutableMap() {
        ImmutableMap immutableMap = key2ResourceBuilder.build();
        setKey2Resource(immutableMap);
        key2ResourceBuilder = ImmutableMap.builder();
    }

    public void writeToFile() {
        setImmutableMap();
        try {
            if (!tempFile.exists()) {
                FileUtils.createFile(tempFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
             SnappyOutputStream outputStream = new SnappyOutputStream(fileOutputStream);) {
            outputStream.write(version);
            ProtostuffUtils.writeListTo(outputStream, staticRes.values());
            logger.info("资源[{}]生成新的缓存文件[{}]", fullFileName, tempFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 从.pbstuff格式的文件中解析
     */
    public boolean tryParseFromPbstuffFile() {
        if (!tempFile.exists()) {
            return false;
        }
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(tempFile));
             SnappyInputStream inputStream = new SnappyInputStream(bufferedInputStream);
        ) {
            long[] versionFromFile = new long[1];
            int size = inputStream.read(versionFromFile);
            if (this.version[0] != versionFromFile[0]) {
                // 版本不一致
                return false;
            }
            List<?> values = ProtostuffUtils.parseListFrom(inputStream, vClass);
            values.forEach(this::add);
            setImmutableMap();
        } catch (IOException e) {
            e.printStackTrace();
            logger.error("静态资源[{}]从缓存文件中加载,加载失败", fullFileName);
            return false;
        }
        logger.info("静态资源[{}]从缓存文件中加载,加载成功", fullFileName);
        return true;
    }

    public void setKey2Resource(ImmutableMap immutableMap) {
        try {
            key2ResourceField.set(staticRes, immutableMap);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @CanIgnoreReturnValue
    public ImmutableMap.Builder add(Object value) {
        if (value.getClass() != vClass) {
            throw new RuntimeException(String.format("添加的对象类型错误，%s的%s添加了类型为%s的对象", fullFileName, StaticResDefinition.class.getSimpleName(), value.getClass().getSimpleName()));
        }
        try {
            Object key = idField.get(value);
            key2ResourceBuilder.put(key, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return key2ResourceBuilder;
    }
}
