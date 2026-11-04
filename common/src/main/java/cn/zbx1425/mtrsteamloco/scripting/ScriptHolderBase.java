package cn.zbx1425.mtrsteamloco.scripting;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.ResourceLocation;
import com.google.gson.JsonObject;
import cn.zbx1425.mtrsteamloco.Main;
import net.minecraft.world.entity.player.Player;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cn.zbx1425.mtrsteamloco.scripting.util.*;
import cn.zbx1425.sowcer.math.*;
import cn.zbx1425.mtrsteamloco.data.ShapeSerializer;
import net.minecraft.network.chat.Component;
import cn.zbx1425.mtrsteamloco.Main;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.FileSystem.Selector;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;

/**
 * Loads and runs one set of JavaScript files (GraalJS with nashorn compatibility) for a vehicle or eye candy.
 * <p>
 * While loading, scripts may use any class; afterwards only the allowed packages and classes. Every call into a script
 * runs on the one script thread, as a Graal context can't be entered by two threads at once; work scripts schedule
 * themselves ({@code ScriptExecutors}) runs there too.
 */
public abstract class ScriptHolderBase {

    // Contexts stay isolated, but sharing the engine lets them reuse parsed code
    private static class SharedEngine {
        private static final Engine INSTANCE = Engine.newBuilder("js")
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        // An engine only accepts contexts that use the same host access instance
        private static final HostAccess HOST_ACCESS =
            HostAccess.newBuilder()
            .allowPublicAccess(true)
            .allowAllImplementations(true)
            .allowAllClassImplementations(true)
            .allowArrayAccess(true)
            .allowListAccess(true)
            .allowBufferAccess(true)
            .allowIterableAccess(true)
            .allowIteratorAccess(true)
            .allowMapAccess(true)
            .allowAccessInheritance(true)
            .allowBigIntegerNumberAccess(true)
            .targetTypeMapping(
                Double.class,
                Double.class,
                value -> true,
                value -> value,
                HostAccess.TargetMappingPrecedence.HIGHEST
            )
            .targetTypeMapping(
                Double.class,
                Float.class,
                value -> true,
                value -> value.floatValue(),
                HostAccess.TargetMappingPrecedence.HIGH
            )
            .targetTypeMapping(
                Double.class,
                Long.class,
                value -> true,
                value -> Math.round(value),
                HostAccess.TargetMappingPrecedence.LOW
            )
            .targetTypeMapping(
                Double.class,
                Integer.class,
                value -> true,
                value -> (int) Math.round(value),
                HostAccess.TargetMappingPrecedence.LOW
            )
            .targetTypeMapping(
                Double.class,
                Short.class,
                value -> true,
                value -> (short) Math.round(value),
                HostAccess.TargetMappingPrecedence.LOW
            )
            .targetTypeMapping(
                Double.class,
                Byte.class,
                value -> true,
                value -> (byte) Math.round(value),
                HostAccess.TargetMappingPrecedence.LOW
            )
            .build();
    }

    private static ExecutorService SCRIPT_THREAD = Executors.newSingleThreadExecutor();

    public final String side;
    private Context context; 
    private Value globalBindings; 
    public final Map<String, List<Value>> functions = new HashMap<>(); 

    public long failTime = 0;
    public Exception failException = null;

    public String name;
    public String contextTypeName;
    private Map<ResourceLocation, String> scripts;

    private JsonObject config;
    private String key;
    private String[] functionNames;

    private final boolean[] loading = new boolean[] { true };

    protected static final String PRETREATMENT = "load(\"nashorn:mozilla_compat.js\");";

    public ScriptHolderBase(String side) {
        this.side = side;
    }

    private static final Set<String> ALLOWED_PACKAGES = Set.of(
        "java.awt", "java.util", "mtr"
    );

    // Single java.lang classes resource packs use at run time (new java.lang.Runnable({...}), new java.lang.Long(id));
    // System, Runtime, Thread, Class and the rest of java.lang stay closed
    private static final Set<String> ALLOWED_CLASSES = Set.of(
        "java.lang.Runnable", "java.lang.Iterable", "java.lang.Comparable", "java.lang.CharSequence",
        "java.lang.Number", "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
        "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Character",
        "java.lang.String", "java.lang.StringBuilder", "java.lang.Math"
    );

    public void load(
        String name, String contextTypeName, ResourceManager resourceManager, 
        Map<ResourceLocation, String> scripts, JsonObject config, String key, 
        String... functionNames) throws Exception {
        this.name = name;
        this.contextTypeName = contextTypeName;
        this.scripts = scripts;
        this.config = config;
        this.key = key;
        this.functionNames = functionNames;

        boolean trust = false;

        final FileSystem defFileSystem = FileSystem.newDefaultFileSystem();
        final Path basicFolder = Path.of("./ante/script_data/").toAbsolutePath().normalize();
        try {
            java.nio.file.Files.createDirectories(basicFolder);
            defFileSystem.setCurrentWorkingDirectory(basicFolder);
        } catch (IOException e) {
            Main.LOGGER.error("Failed to prepare script data folder", e);
        }
        // Everything outside of the data folder is denied, except for what the JS engine itself needs
        final FileSystem denyFileSystem = FileSystem.allowInternalResourceAccess(FileSystem.allowLanguageHomeAccess(FileSystem.newDenyIOFileSystem()));

        context = Context.newBuilder("js")
            .engine(SharedEngine.INSTANCE)
            .allowNativeAccess(false)
            .allowCreateThread(true)
            .allowCreateProcess(false)
            .allowHostClassLoading(true)  
            .allowHostClassLookup(trust ? className -> true : className -> {
                if (loading[0]) return true;
                if (ALLOWED_CLASSES.contains(className)) return true;
                for (String allowedPackage : ALLOWED_PACKAGES) {
                    if (className.startsWith(allowedPackage)) {
                        return true;
                    }
                }
                return false;
            })
            .allowIO(IOAccess.newBuilder().fileSystem(FileSystem.newCompositeFileSystem(denyFileSystem, Selector.of(defFileSystem, path -> {
                if (path.isAbsolute()) {
                    return path.normalize().startsWith(basicFolder);
                } else {
                    return basicFolder.resolve(path).normalize().startsWith(basicFolder);
                }
            }))).build())  
            .allowEnvironmentAccess(EnvironmentAccess.INHERIT)  
            .allowExperimentalOptions(true)  
            .allowInnerContextOptions(true)  
            .sandbox(SandboxPolicy.TRUSTED)
            .allowHostAccess(
                SharedEngine.HOST_ACCESS
            )
            .option("js.nashorn-compat", "true")
            .option("js.ecmascript-version", "latest")
            .option("js.foreign-object-prototype", "true")
            .build();

        if (false) {
        context = Context.newBuilder("js")  
            .sandbox(SandboxPolicy.TRUSTED)
            .allowPolyglotAccess(true ? PolyglotAccess.ALL : PolyglotAccess.NONE)
            .allowNativeAccess(true)
            .option("engine.WarnInterpreterOnly", "false")
            .allowCreateThread(true)  
            .allowCreateProcess(true)
            .allowHostClassLoading(true)  
            .allowHostClassLookup(className -> true)  
            .allowIO(trust ? IOAccess.ALL : IOAccess.NONE)  
            .allowEnvironmentAccess(true ? EnvironmentAccess.INHERIT : EnvironmentAccess.NONE)  
            .allowExperimentalOptions(true)  
            .allowInnerContextOptions(true)  
            .allowValueSharing(true)
            .allowHostAccess(
                HostAccess.newBuilder()
                .allowPublicAccess(true)
                .allowAllImplementations(true)
                .allowAllClassImplementations(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowBufferAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .allowMapAccess(true)
                .allowAccessInheritance(true)
                .allowBigIntegerNumberAccess(true)
                .targetTypeMapping(
                    Double.class,
                    Double.class,
                    value -> true,
                    value -> value,
                    HostAccess.TargetMappingPrecedence.HIGHEST
                )
                .targetTypeMapping(
                    Double.class,
                    Float.class,
                    value -> true,
                    value -> value.floatValue(),
                    HostAccess.TargetMappingPrecedence.HIGH
                )
                .targetTypeMapping(
                    Double.class,
                    Long.class,
                    value -> true,
                    value -> Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .targetTypeMapping(
                    Double.class,
                    Integer.class,
                    value -> true,
                    value -> (int) Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .targetTypeMapping(
                    Double.class,
                    Short.class,
                    value -> true,
                    value -> (short) Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                .targetTypeMapping(
                    Double.class,
                    Byte.class,
                    value -> true,
                    value -> (byte) Math.round(value),
                    HostAccess.TargetMappingPrecedence.LOW
                )
                // .targetTypeMapping(
                //     Double.class,
                //     String.class,
                //     value -> true,
                //     value -> Double.toString(value)
                // )
                // .targetTypeMapping(
                //     Integer.class,
                //     String.class,
                //     value -> true,
                //     value -> Integer.toString(value)
                // )
                // .targetTypeMapping(
                //     Float.class,
                //     String.class,
                //     value -> true,
                //     value -> Float.toString(value)
                // )
                // .targetTypeMapping(
                //     Long.class,
                //     String.class,
                //     value -> true,
                //     value -> Long.toString(value)
                // )
                // .targetTypeMapping(
                //     Short.class,
                //     String.class,
                //     value -> true,
                //     value -> Short.toString(value)
                // )
                // .targetTypeMapping(
                //     Byte.class,
                //     String.class,
                //     value -> true,
                //     value -> Byte.toString(value)
                // )
                .build()
            )
            // .option("js.syntax-extensions", "true")
            .option("js.script-engine-global-scope-import", "true")
            .option("js.ecmascript-version", "latest")
            .option("js.foreign-object-prototype", "true")
            .option("js.strict", "true")
            .option("js.disable-eval", trust ? "false" : "true")
            .option("js.error-cause", "true")
            .option("js.operator-overloading", "true")
            .option("js.profile-time", "true")
            .option("js.nashorn-compat", "true")
            .build();
        }

        globalBindings = context.getBindings("js");    
        
        appendImporter();
        
        for (Map.Entry<ResourceLocation, String> entry : scripts.entrySet()) {
            String scriptContent = entry.getValue() != null ? 
                entry.getValue() : 
                ScriptResourceUtil.readString(entry.getKey());

            ScriptResourceUtil.executeScript(context, scriptContent, entry.getKey());

            for (String fn : functionNames) {
                registerFunction(fn);
                registerFunction(fn + contextTypeName);
            }
        }

        loading[0] = false;
    }

    public void reload(ResourceManager resourceManager) throws Exception {
        close();
        load(name, contextTypeName, resourceManager, scripts, config, key, functionNames);
    }

    protected void inject(Class clazz, String method, String alias) {
        if (alias == null) alias = method;
        context.eval("js", "var " + alias + " = Java.type('" + clazz.getName() + "')." + method + ";");
    }

    protected void inject(Class clazz, String alias) {
        if (alias == null) alias = clazz.getSimpleName();
        context.eval("js", "var " + alias + " = Java.type('" + clazz.getName() + "');");
    }

    protected void inject(String key, String value) {
        context.eval("js", "var " + key + " = '" + value + "';");
    }

    protected void eval(String script) {
        context.eval("js", script);
    }

    protected void appendImporter() {
        eval(PRETREATMENT);
        inject("SIDE", side);
        Gson configGson = new GsonBuilder().disableHtmlEscaping().create();
        eval("CONFIG_INFO = JSON.parse(" + configGson.toJson(configGson.toJson(config)) + ");");
        inject("MOD_ENV", Main.class.getPackageName().split("\\.")[0]);

        inject(ScriptResourceUtil.class, "includeScript", "include");
        inject(ScriptResourceUtil.class, "print", "print");
        inject(JsFriendlyJavaUtils.class, "asJavaArray", "asJavaArray");

        inject(TimingUtil.class, "Timing");
        inject(StateTracker.class, "StateTracker");
        inject(CycleTracker.class, "CycleTracker");
        inject(RateLimit.class, "RateLimit");
        inject(TextUtil.class, "TextUtil");
        inject(GlobalRegister.class, "GlobalRegister");
        inject(WrappedEntity.class, "WrappedEntity");
        inject(ComponentUtil.class, "ComponentUtil");
        inject(OrderedMap.class, "OrderedMap");
        inject(OrderedMap.PlacementOrder.class, "PlacementOrder");
        inject(ShapeSerializer.class, "ShapeSerializer");
        // Shadows importPackage(java.util.concurrent): script timers run on the script thread
        inject(ScriptExecutors.class, "Executors");

        inject(Matrices.class, "Matrices");
        inject(Matrix4f.class, "Matrix4f");
        inject(Vector3f.class, "Vector3f");   

        inject(Component.class, "Component");
        
        inject(Optional.class, "Optional");
    }

    private void registerFunction(String name) {
        Value func = globalBindings.getMember(name);
        if (func != null && func.canExecute()) {
            functions.computeIfAbsent(name, k -> new ArrayList<>())
                     .add(func);
            eval("delete " + name + ";");
        }
    }

    public Future<?> callFunctionAsync(List<Value> functions, AbstractScriptContext scriptCtx, 
                                      Runnable finishCallback, Object... args) {
        if (duringFailTimeout()) return null;
        if (context == null) {
            Main.LOGGER.error("Script context is null, cannot execute function");
            return null;
        }

        return SCRIPT_THREAD.submit(() -> {
            long start = System.nanoTime();
            try {            
                TimingUtil.prepareForScript(scriptCtx);    
                Object[] allArgs = new Object[3 + args.length];
                allArgs[0] = scriptCtx;
                allArgs[1] = scriptCtx.state != null ? scriptCtx.state : ProxyObject.fromMap(new HashMap<>());
                allArgs[2] = scriptCtx.getWrapperObject();
                System.arraycopy(args, 0, allArgs, 3, args.length);
                
                for (Value function : functions) {
                    function.executeVoid(allArgs);
                }
                
                if (finishCallback != null) finishCallback.run();

                failException = null;
                failTime = 0;
            } catch (Exception ex) {
                Main.LOGGER.error("Error in ANTE Resource Pack JavaScript", ex);
                failTime = System.currentTimeMillis();
                failException = ex;
            }
            scriptCtx.lastExecuteDuration = System.nanoTime() - start;
        });
    }

    public void tryCallFunctionAsync(String function, AbstractScriptContext scriptCtx, Runnable callback, Object... args) {
        if (!(scriptCtx.scriptStatus == null || scriptCtx.scriptStatus.isDone())) return;
        if (scriptCtx.disposed) return;
        List<Value> functions = this.functions.get(function);
        if (functions == null) functions = new ArrayList<>();
        scriptCtx.scriptStatus = callFunctionAsync(functions, scriptCtx, callback, args);
    }

    public void tryCallRenderFunctionAsync(AbstractScriptContext scriptCtx) {
        ScriptContextManager.trackContext(scriptCtx, this);
        if (!scriptCtx.created) {
            tryCallFunctionAsync("create", scriptCtx, () -> scriptCtx.created = true);
        } else {
            tryCallFunctionAsync("render", scriptCtx, () -> scriptCtx.renderFunctionFinished(), true);
        }
    }

    public void tryCallDisposeFunctionAsync(AbstractScriptContext scriptCtx) {
        tryCallFunctionAsync("dispose", scriptCtx, () -> scriptCtx.created = false, false);
        scriptCtx.disposed = true;
    }

    public void tryCallUseFunctionAsync(AbstractScriptContext scriptCtx, Player player) {
        tryCallFunctionAsync("use", scriptCtx, null, true, new WrappedEntity(player));
    }

    private boolean duringFailTimeout() {
        return failTime > 0 && (System.currentTimeMillis() - failTime) < 4000;
    }

    // Runs a task from another thread on the script thread and waits for it; must not be called from the script thread
    public static <V> V runOnScriptThread(Callable<V> task) throws Exception {
        try {
            return SCRIPT_THREAD.submit(task).get();
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof Exception ? (Exception) ex.getCause() : ex;
        }
    }

    public static void resetRunner() {
        ScriptExecutors.shutdownAll();
        SCRIPT_THREAD.shutdownNow();
        SCRIPT_THREAD = Executors.newSingleThreadExecutor();
    }

    public void close() {
        if (context != null) {
            context.close();
            context = null;
        }
    }
}