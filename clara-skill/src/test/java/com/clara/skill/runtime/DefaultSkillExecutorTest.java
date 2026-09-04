package com.clara.skill.runtime;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillEventObserver;
import com.clara.skill.api.SkillExecutionHandle;
import com.clara.skill.api.SkillExecutionRequest;
import com.clara.skill.api.SkillExecutionResult;
import com.clara.skill.api.SkillExecutionStatus;
import com.clara.skill.api.SkillExecutionType;
import com.clara.skill.api.SkillHealth;
import com.clara.skill.api.SkillResumeRequest;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultSkillExecutorTest {
    private DefaultSkillExecutor executor;

    @After public void tearDown(){if(executor!=null)executor.close();}

    @Test
    public void routesPinnedVersionAndSupportsResume(){
        InMemorySkillRegistry registry=published(); final AtomicInteger resumes=new AtomicInteger();
        SkillHandler handler=new SkillHandler(){
            public SkillExecutionResult execute(SkillDefinition d,SkillExecutionRequest r){return result(d,"instance",r.getInputs());}
            public SkillExecutionResult resume(SkillDefinition d,SkillResumeRequest r){resumes.incrementAndGet();return result(d,r.getInstanceId(),r.getFeedback());}
        };
        executor=executor(registry,handler,allow(),SkillHealth.HEALTHY,500L);
        SkillExecutionRequest request=request();
        assertEquals("value",executor.execute(request).getOutputs().get("input"));
        SkillResumeRequest resume=new SkillResumeRequest(request,"instance",2L,Collections.<String,Object>singletonMap("answer","yes"));
        assertEquals("yes",executor.resume(resume).getOutputs().get("answer"));assertEquals(1,resumes.get());
    }

    @Test
    public void rejectsPermissionBlockedHealthAndInvalidSchema(){
        final InMemorySkillRegistry registry=published(); SkillHandler handler=successful();
        executor=executor(registry,handler,new SkillPermissionAuthorizer(){public boolean isAllowed(InvocationContext c,SkillDefinition d){return false;}},SkillHealth.HEALTHY,500L);
        assertError(ErrorCodes.SKILL_PERMISSION_DENIED,new Runnable(){public void run(){executor.execute(request());}});executor.close();
        executor=executor(registry,handler,allow(),SkillHealth.BLOCKED,500L);
        assertError(ErrorCodes.SKILL_UNAVAILABLE,new Runnable(){public void run(){executor.execute(request());}});executor.close();
        executor=executor(registry,handler,allow(),SkillHealth.HEALTHY,500L,new SkillSchemaValidator(){
            public boolean isValid(String schema,Map<String,Object> value){return false;}});
        assertError(ErrorCodes.SKILL_SCHEMA_INVALID,new Runnable(){public void run(){executor.execute(request());}});
    }

    @Test
    public void enforcesTimeout(){
        InMemorySkillRegistry registry=published(20L);
        SkillHandler slow=new SkillHandler(){public SkillExecutionResult execute(SkillDefinition d,SkillExecutionRequest r)throws Exception{Thread.sleep(1000L);return result(d,"i",null);}
            public SkillExecutionResult resume(SkillDefinition d,SkillResumeRequest r)throws Exception{return execute(d,r.getExecutionRequest());}};
        executor=executor(registry,slow,allow(),SkillHealth.HEALTHY,20L);
        assertError(ErrorCodes.SKILL_TIMEOUT,new Runnable(){public void run(){executor.execute(request());}});
    }

    @Test
    public void streamsThroughSameExecutionPath(){
        InMemorySkillRegistry registry=published();executor=executor(registry,successful(),allow(),SkillHealth.HEALTHY,500L);
        final CountDownLatch completed=new CountDownLatch(1);final AtomicReference<SkillExecutionResult> value=new AtomicReference<SkillExecutionResult>();
        SkillExecutionHandle handle=executor.executeStream(request(),new SkillEventObserver(){
            public void onStarted(String id,String version){} public void onCompleted(SkillExecutionResult result){value.set(result);completed.countDown();}
            public void onError(Throwable failure){completed.countDown();}});
        try{assertTrue(completed.await(2L,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();fail();}
        assertTrue(handle.isDone());assertEquals(SkillExecutionStatus.SUCCEEDED,value.get().getStatus());
    }

    private static InMemorySkillRegistry published(){return published(500L);}
    private static InMemorySkillRegistry published(long timeout){InMemorySkillRegistry registry=InMemorySkillRegistryTest.registry(true);
        SkillDefinition definition=InMemorySkillRegistryTest.definition("skill","1",null);
        definition=SkillDefinition.builder().from(definition).timeoutMillis(timeout).build();
        InMemorySkillRegistryTest.publish(registry,definition);return registry;}
    private static SkillExecutionRequest request(){return new SkillExecutionRequest(new InvocationContext("tenant","user","run","instance","trace",
            System.currentTimeMillis()+5000L,new CancellationToken()),"skill","1",Collections.<String,Object>singletonMap("input","value"));}
    private static SkillExecutionResult result(SkillDefinition d,String instance,Map<String,Object> outputs){return new SkillExecutionResult(
            d.getSkillId(),d.getVersion(),instance,SkillExecutionStatus.SUCCEEDED,outputs,1L);}
    private static SkillHandler successful(){return new SkillHandler(){public SkillExecutionResult execute(SkillDefinition d,SkillExecutionRequest r){return result(d,"i",r.getInputs());}
        public SkillExecutionResult resume(SkillDefinition d,SkillResumeRequest r){return result(d,r.getInstanceId(),r.getFeedback());}};}
    private static SkillPermissionAuthorizer allow(){return new SkillPermissionAuthorizer(){public boolean isAllowed(InvocationContext c,SkillDefinition d){return true;}};}
    private static DefaultSkillExecutor executor(InMemorySkillRegistry r,SkillHandler h,SkillPermissionAuthorizer p,final SkillHealth health,long ignored){
        return executor(r,h,p,health,ignored,new SkillSchemaValidator(){public boolean isValid(String s,Map<String,Object> v){return true;}});}
    private static DefaultSkillExecutor executor(InMemorySkillRegistry r,SkillHandler h,SkillPermissionAuthorizer p,final SkillHealth health,long ignored,SkillSchemaValidator schema){
        Map<SkillExecutionType,SkillHandler> handlers=new EnumMap<SkillExecutionType,SkillHandler>(SkillExecutionType.class);handlers.put(SkillExecutionType.JAVA_HANDLER,h);
        return new DefaultSkillExecutor(r,schema,p,new SkillHealthResolver(){public SkillHealth health(SkillDefinition d){return health;}},handlers,2);}
    private static void assertError(String code,Runnable action){try{action.run();fail("expected "+code);}
        catch(ClaraException exception){assertEquals(code,exception.getError().getCode());}}
}
