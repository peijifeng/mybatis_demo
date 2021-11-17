package sample.mybatis.xml.helper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.nio.file.StandardWatchEventKinds.*;

public class RefreshableSqlSessionFactoryBean extends SqlSessionFactoryBean implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(RefreshableSqlSessionFactoryBean.class);
    private SqlSessionFactory sqlSessionFactoryProxy;
    private int interval = 500;
    private Timer timer;
    private TimerTask task;
    private Resource[] mapperLocations;
    private boolean running = false;
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final Lock readLock = rwl.readLock();
    private final Lock writeLock = rwl.writeLock();
    private static final String MAVEN_TARGET_DIR = "target".concat(File.separator).concat("classes");
    private static final String MAVEN_SRC_DIR = "src".concat(File.separator).concat("main").concat(File.separator).concat("resources");
    private final String[] watchFileTypes = new String[]{"Mapper.xml"};

    @Override
    public void setMapperLocations(Resource[] mapperLocations) {
        super.setMapperLocations(mapperLocations);
        this.mapperLocations = mapperLocations;
        watchPath(mapperLocations);
    }

    private void watchPath(Resource[] mapperLocations) {
        Set<Path> paths = new HashSet<>(mapperLocations.length);
        try {
            for (Resource resource : mapperLocations) {
                if (resource.isFile()) {
                    String srcPath = resource.getFile().getParentFile().getCanonicalPath().replaceAll(MAVEN_TARGET_DIR, MAVEN_SRC_DIR);
                    paths.add(Paths.get(srcPath));
                }
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }

        logger.info("Watch paths: {}", paths);
        ExecutorService es = Executors.newSingleThreadExecutor();
        es.execute(() -> {
            try {
                watchPath(paths);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        });
    }

    private void watchPath(Set<Path> paths) throws IOException {
        WatchService watchService = null;

        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (Path path : paths) {
                path.register(watchService, ENTRY_MODIFY, ENTRY_DELETE, ENTRY_CREATE);
            }

            WatchKey watchKey = null;

            while (true) {
                watchKey = watchService.take();

                for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
                    WatchEvent.Kind<?> kind = watchEvent.kind();
                    Path path = ((WatchEvent<Path>) watchEvent).context();

                    if (isWatchTypefile(path.toFile().getName())) {
                        if (kind == OVERFLOW) {
                            continue;
                        } else if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY || kind == ENTRY_DELETE) {
                            Class c = watchKey.getClass().getSuperclass();
                            Method method = c.getDeclaredMethod("watchable");
                            method.setAccessible(true);
                            Path p = (Path) method.invoke(watchKey);
                            String srcFile = p.toString().concat(File.separator).concat(path.toFile().getName());
                            logger.info("srcFile changed: {}", srcFile);
                            String targetFile = srcFile.replaceAll(MAVEN_SRC_DIR, MAVEN_TARGET_DIR);
                            if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                                Files.copy(Paths.get(srcFile), Paths.get(targetFile), StandardCopyOption.REPLACE_EXISTING);
                                logger.info("targetFile updated: {}", targetFile);
                            } else if (kind == ENTRY_DELETE) {
                                Files.deleteIfExists(Paths.get(targetFile));
                                logger.info("targetFile deleted: {}", targetFile);
                            }
                        }
                    }
                }

                if (!watchKey.reset()) {
                    break;
                }
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InterruptedException e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (watchService != null) {
                    watchService.close();
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private boolean isWatchTypefile(String filename) {
        return Arrays.stream(watchFileTypes).anyMatch(t -> filename.endsWith(t));
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public void refresh() throws Exception {
        writeLock.lock();
        try {
            super.afterPropertiesSet();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();
        setRefreshable();
    }

    private void setRefreshable() {

        sqlSessionFactoryProxy = (SqlSessionFactory) Proxy.newProxyInstance(SqlSessionFactory.class.getClassLoader(),
                new Class[]{SqlSessionFactory.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return method.invoke(getParentObject(), args);
                    }
                });

        task = new TimerTask() {
            private Map<Resource, Long> map = new HashMap<>();

            @Override
            public void run() {
                if (isModified()) {
                    try {
                        refresh();
                    } catch (Exception e) {
                        logger.error("caught exception", e);
                    }
                }
            }

            private boolean isModified() {
                boolean retVal = false;
                if (mapperLocations != null) {
                    for (int i = 0; i < mapperLocations.length; i++) {
                        Resource mappingLocation = mapperLocations[i];
                        retVal |= findModifiedResource(mappingLocation);
                    }
                }
                return retVal;
            }


            private boolean findModifiedResource(Resource resource) {
                boolean retVal = false;
                List<String> modifiedResources = new ArrayList<>();
                try {
                    long modified = resource.lastModified();
                    if (map.containsKey(resource)) {
                        long lastModified = map.get(resource);
                        if (lastModified != modified) {
                            map.put(resource, modified);
                            modifiedResources.add(resource.getDescription());
                            retVal = true;
                        }
                    } else {
                        map.put(resource, modified);
                    }
                } catch (IOException e) {
                    logger.error("caught exception", e);
                }
                return retVal;

            }

        };

        timer = new Timer(true);
        resetInterval();
    }

    private Object getParentObject() throws Exception {
        readLock.lock();
        try {
            return super.getObject();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public SqlSessionFactory getObject() {
        return this.sqlSessionFactoryProxy;
    }

    @Override
    public Class<? extends SqlSessionFactory> getObjectType() {
        return (this.sqlSessionFactoryProxy != null ? this.sqlSessionFactoryProxy.getClass() : SqlSessionFactory.class);
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    private void resetInterval() {
        if (running) {
            timer.cancel();
            running = false;
        }
        if (interval > 0) {
            timer.schedule(task, 0, interval);
            running = true;
        }
    }

    public void destroy() throws Exception {
        timer.cancel();
    }
}
