package org.yamcs.parameter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.yamcs.AbstractYamcsService;
import org.yamcs.ConfigurationException;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.protobuf.Yamcs;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.time.TimeService;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AbsoluteTimeParameterType;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.ArrayParameterType;
import org.yamcs.xtce.BinaryParameterType;
import org.yamcs.xtce.BooleanParameterType;
import org.yamcs.xtce.EnumeratedParameterType;
import org.yamcs.xtce.FloatParameterType;
import org.yamcs.xtce.IntegerParameterType;
import org.yamcs.xtce.Member;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.ParameterType;
import org.yamcs.xtce.StringParameterType;
import org.yamcs.xtce.SystemParameter;
import org.yamcs.xtce.XtceDb;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import com.google.common.io.Files;

import static org.yamcs.xtce.XtceDb.YAMCS_SPACESYSTEM_NAME;
import static org.yamcs.xtce.NameDescription.qualifiedName;

/**
 * Collects each second system processed parameters from whomever registers and sends them on the sys_var stream
 * <p>
 * Starting with Yamcs 5.5.0, all system parameters have types defined in the MDB. For the basic types (corresponding to
 * scalar values), this class will provide some types (e.g. uint65, float32, etc)
 * <p>
 * For aggregate, the caller can use the {@link #createSystemParameter(String, AggregateParameterType, String)} to make
 * the parameter and also add the corresponding type to the MDB.
 *
 * @author nm
 *
 */
public class SystemParametersService extends AbstractYamcsService implements Runnable {

    static Map<String, SystemParametersService> instances = new HashMap<>();
    static long frequencyMillisec = 1000;
    List<SysVarProducer> providers = new CopyOnWriteArrayList<>();

    static final String STREAM_NAME = "sys_param";

    Stream stream;

    int seqCount = 0;

    // /yamcs/<server_id>
    private String namespace;
    private String serverId;
    XtceDb mdb;

    TimeService timeService;
    ScheduledFuture<?> collectionFuture;

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("provideJvmVariables", OptionType.BOOLEAN).withDefault(false);
        spec.addOption("provideFsVariables", OptionType.BOOLEAN).withDefault(false);
        return spec;
    }

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);
        mdb = YamcsServer.getServer().getInstance(yamcsInstance).getXtceDb();

        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        stream = ydb.getStream(STREAM_NAME);
        if (stream == null) {
            throw new ConfigurationException("Stream '" + STREAM_NAME + "' does not exist");
        }

        serverId = YamcsServer.getServer().getServerId();
        namespace = XtceDb.YAMCS_SPACESYSTEM_NAME + NameDescription.PATH_SEPARATOR + serverId;

        log.debug("Using {} as serverId, and {} as namespace for system parameters", serverId, namespace);
        if (config.getBoolean("provideJvmVariables")) {
            providers.add(new SysVarProducer(new JvmParameterProducer(this)));
        }

        if (config.getBoolean("provideFsVariables")) {
            providers.add(new SysVarProducer(new FileStoreParameterProducer(this)));
        }

        synchronized (instances) {
            instances.put(yamcsInstance, this);
        }
    }

    public static SystemParametersService getInstance(String instance) {
        synchronized (instances) {
            return instances.get(instance);
        }
    }

    @Override
    public void doStart() {
        YamcsServer server = YamcsServer.getServer();
        timeService = server.getInstance(yamcsInstance).getTimeService();
        ScheduledThreadPoolExecutor timer = server.getThreadPoolExecutor();
        collectionFuture = timer.scheduleAtFixedRate(this, 1000L, frequencyMillisec, TimeUnit.MILLISECONDS);
        notifyStarted();
    }

    @Override
    public void doStop() {
        collectionFuture.cancel(true);
        synchronized (instances) {
            instances.remove(yamcsInstance);
        }
        try {
            collectionFuture.get();
            notifyStopped();
        } catch (CancellationException e) {
            notifyStopped();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    /**
     * Run from the timer, collect all parameters and send them on the stream
     */
    @Override
    public void run() {
        long gentime = timeService.getMissionTime();

        List<ParameterValue> params = new ArrayList<>();

        for (SysVarProducer svp : providers) {
            svp.count++;
            if (svp.count >= svp.freq) {
                svp.count = 0;
                try {
                    Collection<ParameterValue> pvc = svp.producer.getSystemParameters(gentime);
                    params.addAll(pvc);
                } catch (Exception e) {
                    log.warn("Error getting parameters from provider {}", svp.producer, e);
                }
            }
        }

        if (params.isEmpty()) {
            return;
        }
        TupleDefinition tdef = StandardTupleDefinitions.PARAMETER.copy();
        List<Object> cols = new ArrayList<>(4 + params.size());
        cols.add(gentime);
        cols.add(namespace);
        cols.add(seqCount);
        cols.add(gentime);
        for (ParameterValue pv : params) {
            if (pv == null) {
                log.error("Null parameter value encountered, skipping");
                continue;
            }
            String name = pv.getParameterQualifiedName();
            int idx = tdef.getColumnIndex(name);
            if (idx != -1) {
                log.warn("duplicate value for {}\nfirst: {}\n second: {}", name, cols.get(idx), pv);
                continue;
            }
            tdef.addColumn(name, DataType.PARAMETER_VALUE);
            cols.add(pv);
        }
        Tuple t = new Tuple(tdef, cols);
        stream.emitTuple(t);
    }

    /**
     * Register a parameter producer to be called each time the parameters are collected
     */
    public void registerProducer(SystemParametersProducer p) {
        log.debug("Registering system variables producer {}", p);
        if (providers.stream().anyMatch(spv -> spv.producer == p)) {
            throw new IllegalStateException("Producer already registered");
        }
        providers.add(new SysVarProducer(p));
    }

    /**
     * Unregister producer - from now on it will not be invoked. Note that the collector collects parameters into a
     * different thread taking all producer in turns, and there might be one collection already started when this method
     * is called.
     *
     */
    public void unregisterProducer(SystemParametersProducer p) {
        log.debug("Unregistering system variables producer {}", p);
        providers.stream().filter(spv -> spv.producer == p).forEach(svp -> providers.remove(svp));
    }

    /**
     * this is the namespace all system parameters should be in
     *
     * @return the namespace to be used by the system parameters
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Creates a system parameter for an aggregate type.
     * <p>
     * If the type has no qualified name, one is set and is added to the MDB. Otherwise it is assumed it already comes
     * from he MDB and it is not added.
     * 
     * @param relativeName
     * @param type
     * @return
     */
    public SystemParameter createSystemParameter(String relativeName, AggregateParameterType type, String description) {
        relativeName = Files.simplifyPath(relativeName);
        if (relativeName.startsWith("/")) {
            throw new IllegalArgumentException("The name has to be relative");
        }

        if (type.getQualifiedName() == null) {
            ((NameDescription) type).setQualifiedName(qualifiedName(namespace, type.getName()));
            type = (AggregateParameterType) mdb.addSystemParameterType(type);
        }

        return mdb.createSystemParameter(qualifiedName(namespace, relativeName), type, description);
    }

    /**
     * Create a system parameter for a basic value type. The created parameter will have a shared basic parameter type.
     * <p>
     * If the type is aggregate, the method {@link #createSystemParameter(String, AggregateParameterType, String)}
     * should be used after making an appropriate {@link AggregateParameterType}
     * 
     * @param relativeName
     *            - the relative name of the parameter, can contain multiple subsystems but cannot start with "/"
     * 
     * @param basicType
     *            - any type except aggregate and array
     * @return
     */
    public SystemParameter createSystemParameter(String relativeName, Yamcs.Value.Type basicType, String description) {
        relativeName = Files.simplifyPath(relativeName);
        if (relativeName.startsWith("/")) {
            throw new IllegalArgumentException("The name has to be relative");
        }
        return createSystemParameter(mdb, qualifiedName(namespace, relativeName), basicType, description);
    }

    public static SystemParameter createSystemParameter(XtceDb mdb, String fqn, Value engValue) {
        String name = NameDescription.getName(fqn);
        ParameterType ptype = createSystemParameterType(mdb, name, engValue);
        return mdb.createSystemParameter(fqn, ptype, null);
    }

    public static SystemParameter createSystemParameter(XtceDb mdb, String fqn, Yamcs.Value.Type basicType,
            String description) {
        ParameterType ptype = getBasicType(mdb, basicType);
        return mdb.createSystemParameter(fqn, ptype, description);
    }

    public EnumeratedParameterType createEnumeratedParameterType(Class<? extends Enum<?>> enumClass) {
        String typeName = enumClass.getCanonicalName().replace(".", "_");
        EnumeratedParameterType type = (EnumeratedParameterType) mdb.getParameterType(YAMCS_SPACESYSTEM_NAME, typeName);
        if (type == null) {
            EnumeratedParameterType.Builder etypeb = new EnumeratedParameterType.Builder();
            etypeb.setName(typeName);
            etypeb.setQualifiedName(qualifiedName(YAMCS_SPACESYSTEM_NAME, typeName));
            for (Enum<?> x : enumClass.getEnumConstants()) {
                etypeb.addEnumerationValue(x.ordinal(), x.name());
            }
            type = (EnumeratedParameterType) mdb.addSystemParameterType(etypeb.build());
        }
        return type;
    }

    /**
     * Creates an enumerated system parameter by deducing the possible enumeration states from the java enum.
     */
    public SystemParameter createEnumeratedSystemParameter(String relativeName, Class<? extends Enum<?>> enumClass,
            String description) {
        EnumeratedParameterType type = createEnumeratedParameterType(enumClass);
        return mdb.createSystemParameter(qualifiedName(namespace, relativeName), type, description);
    }

    public static ParameterType createSystemParameterType(XtceDb mdb, String name, Value v) {
        if (v instanceof AggregateValue) {
            AggregateValue aggrv = (AggregateValue) v;
            AggregateParameterType.Builder aggrType = new AggregateParameterType.Builder();
            aggrType.setName(name).setQualifiedName(qualifiedName(YAMCS_SPACESYSTEM_NAME, name));

            for (int i = 0; i < aggrv.numMembers(); i++) {
                String mname = aggrv.getMemberName(i);
                Value mvalue = aggrv.getMemberValue(i);
                Member m = new Member(mname);
                ParameterType mtype = createSystemParameterType(mdb, name + "." + mname, mvalue);
                m.setDataType(mtype);
                aggrType.addMember(m);
            }
            return mdb.addSystemParameterType(aggrType.build());
        } else if (v instanceof ArrayValue) {
            ArrayValue av = (ArrayValue) v;
            if (av.flatLength() == 0) {
                throw new IllegalArgumentException("Cannot create a type for an empty array "
                        + "because the elemnt type cannot be determined");
            }
            ParameterType elementType = createSystemParameterType(mdb, name + "[]", av.getElementValue(0));
            ArrayParameterType arrayType = new ArrayParameterType.Builder()
                    .setName(name)
                    .setQualifiedName(qualifiedName(YAMCS_SPACESYSTEM_NAME, name))
                    .setElementType(elementType)
                    .build();
            return mdb.addSystemParameterType(arrayType);
        } else {
            return getBasicType(mdb, v.getType());
        }
    }

    /**
     * Create (if not already existing) a basic parameter type in the MDB and return it.
     * <p>
     * Basic type is everything except aggregate and arrays
     * 
     * @param type
     * @return
     */
    public ParameterType getBasicType(Yamcs.Value.Type type) {
        return getBasicType(mdb, type);
    }

    public static ParameterType getBasicType(XtceDb mdb, Type type) {
        switch (type) {
        case BINARY:
            return getOrCreateType(mdb, "binary",
                    () -> new BinaryParameterType.Builder());
        case BOOLEAN:
            return getOrCreateType(mdb, "boolean",
                    () -> new BooleanParameterType.Builder());
        case STRING:
            return getOrCreateType(mdb, "string",
                    () -> new StringParameterType.Builder());
        case FLOAT:
            return getOrCreateType(mdb, "float32",
                    () -> new FloatParameterType.Builder().setSizeInBits(32));
        case DOUBLE:
            return getOrCreateType(mdb, "float64",
                    () -> new FloatParameterType.Builder().setSizeInBits(64));
        case SINT32:
            return getOrCreateType(mdb, "sint32",
                    () -> new IntegerParameterType.Builder().setSizeInBits(32).setSigned(true));
        case SINT64:
            return getOrCreateType(mdb, "sint64",
                    () -> new IntegerParameterType.Builder().setSizeInBits(64).setSigned(true));
        case UINT32:
            return getOrCreateType(mdb, "uint32",
                    () -> new IntegerParameterType.Builder().setSizeInBits(32).setSigned(false));
        case UINT64:
            return getOrCreateType(mdb, "uint64",
                    () -> new IntegerParameterType.Builder().setSizeInBits(64).setSigned(false));
        case TIMESTAMP:
            return getOrCreateType(mdb, "time", () -> new AbsoluteTimeParameterType.Builder());
        case ENUMERATED:
            return getOrCreateType(mdb, "enum", () -> new EnumeratedParameterType.Builder());
        default:
            throw new IllegalArgumentException(type + "is not a basic type");
        }
    }

    public ParameterType getOrCreateType(String name, Supplier<ParameterType.Builder<?>> supplier) {
        return getOrCreateType(mdb, name, supplier);
    }

    static private ParameterType getOrCreateType(XtceDb mdb, String name, Supplier<ParameterType.Builder<?>> supplier) {
        String fqn = XtceDb.YAMCS_SPACESYSTEM_NAME + NameDescription.PATH_SEPARATOR + name;
        ParameterType ptype = mdb.getParameterType(fqn);
        if (ptype != null) {
            return ptype;
        }
        ptype = supplier.get().setName(name).build();
        ((NameDescription) ptype).setQualifiedName(fqn);

        return mdb.addSystemParameterType(ptype);
    }

    public static ParameterValue getNewPv(Parameter parameter, long time) {
        ParameterValue pv = new ParameterValue(parameter);
        pv.setAcquisitionTime(time);
        pv.setGenerationTime(time);
        return pv;
    }

    public static ParameterValue getPV(Parameter parameter, long time, String v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getStringValue(v));
        return pv;
    }

    public static ParameterValue getPV(Parameter parameter, long time, double v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getDoubleValue(v));
        return pv;
    }

    public static ParameterValue getPV(Parameter parameter, long time, float v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getFloatValue(v));
        return pv;
    }

    public static ParameterValue getPV(Parameter parameter, long time, boolean v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getBooleanValue(v));
        return pv;
    }

    public static ParameterValue getPV(Parameter parameter, long time, long v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getSint64Value(v));
        return pv;
    }

    public static ParameterValue getUnsignedIntPV(Parameter parameter, long time, int v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getUint64Value(v));
        return pv;
    }

    public static <T extends Enum<T>> ParameterValue getPV(Parameter parameter, long time, T v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(ValueUtility.getEnumeratedValue(v.ordinal(), v.name()));
        return pv;
    }

    public static ParameterValue getPV(Parameter parameter, long time, Value v) {
        ParameterValue pv = getNewPv(parameter, time);
        pv.setEngValue(v);
        return pv;
    }

    // the methods below are deprecated because they create parameter values without types
    @Deprecated
    public static ParameterValue getNewPv(String fqn, long time) {
        ParameterValue pv = new ParameterValue(fqn);
        pv.setAcquisitionTime(time);
        pv.setGenerationTime(time);
        return pv;
    }

    @Deprecated
    public static ParameterValue getPV(String fqn, long time, String v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(ValueUtility.getStringValue(v));
        return pv;
    }

    @Deprecated
    public static ParameterValue getPV(String fqn, long time, double v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(ValueUtility.getDoubleValue(v));
        return pv;
    }

    @Deprecated
    public static ParameterValue getPV(String fqn, long time, float v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(ValueUtility.getFloatValue(v));
        return pv;
    }

    @Deprecated
    public static ParameterValue getPV(String fqn, long time, boolean v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(ValueUtility.getBooleanValue(v));
        return pv;
    }

    @Deprecated
    public static ParameterValue getPV(String fqn, long time, long v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(ValueUtility.getSint64Value(v));
        return pv;
    }

    @Deprecated
    public static ParameterValue getUnsignedIntPV(String fqn, long time, int v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(ValueUtility.getUint64Value(v));
        return pv;
    }

    @Deprecated
    public static ParameterValue getPV(String fqn, long time, Value v) {
        ParameterValue pv = getNewPv(fqn, time);
        pv.setEngValue(v);
        return pv;
    }

    public XtceDb getMdb() {
        return mdb;
    }

    class SysVarProducer {
        SystemParametersProducer producer;
        int freq;
        int count;

        SysVarProducer(SystemParametersProducer producer) {
            this.producer = producer;
            this.freq = producer.getFrequency();
            this.count = this.freq;
        }

    }

}
