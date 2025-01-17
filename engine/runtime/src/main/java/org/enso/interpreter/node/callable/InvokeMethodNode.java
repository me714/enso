package org.enso.interpreter.node.callable;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import org.enso.interpreter.Language;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.dispatch.InvokeFunctionNode;
import org.enso.interpreter.node.callable.resolver.*;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.Context;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.callable.argument.CallArgumentInfo;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.ArrayRope;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.*;
import org.enso.interpreter.runtime.library.dispatch.MethodDispatchLibrary;
import org.enso.interpreter.runtime.state.Stateful;

@ImportStatic({HostMethodCallNode.PolyglotCallType.class, HostMethodCallNode.class})
public abstract class InvokeMethodNode extends BaseNode {
  private @Child InvokeFunctionNode invokeFunctionNode;
  private final ConditionProfile errorReceiverProfile = ConditionProfile.createCountingProfile();
  private final BranchProfile polyglotArgumentErrorProfile = BranchProfile.create();
  private @Child InvokeMethodNode childDispatch;
  private final int argumentCount;
  private final int thisArgumentPosition;

  /**
   * Creates a new node for method invocation.
   *
   * @param schema a description of the arguments being applied to the callable
   * @param defaultsExecutionMode the defaulted arguments handling mode for this call
   * @param argumentsExecutionMode the arguments execution mode for this call
   * @return a new invoke method node
   */
  public static InvokeMethodNode build(
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      int thisArgumentPosition) {
    return InvokeMethodNodeGen.create(
        schema, defaultsExecutionMode, argumentsExecutionMode, thisArgumentPosition);
  }

  InvokeMethodNode(
      CallArgumentInfo[] schema,
      InvokeCallableNode.DefaultsExecutionMode defaultsExecutionMode,
      InvokeCallableNode.ArgumentsExecutionMode argumentsExecutionMode,
      int thisArgumentPosition) {
    this.invokeFunctionNode =
        InvokeFunctionNode.build(schema, defaultsExecutionMode, argumentsExecutionMode);
    this.argumentCount = schema.length;
    this.thisArgumentPosition = thisArgumentPosition;
  }

  @Override
  public void setTailStatus(TailStatus tailStatus) {
    super.setTailStatus(tailStatus);
    this.invokeFunctionNode.setTailStatus(tailStatus);
    if (childDispatch != null) {
      childDispatch.setTailStatus(tailStatus);
    }
  }

  public abstract Stateful execute(
      VirtualFrame frame, Object state, UnresolvedSymbol symbol, Object _this, Object[] arguments);

  @Specialization(guards = "dispatch.hasFunctionalDispatch(_this)")
  Stateful doFunctionalDispatch(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      Object _this,
      Object[] arguments,
      @CachedLibrary(limit = "10") MethodDispatchLibrary dispatch) {
    try {
      Function function = dispatch.getFunctionalDispatch(_this, symbol);
      return invokeFunctionNode.execute(function, frame, state, arguments);
    } catch (MethodDispatchLibrary.NoSuchMethodException e) {
      throw new PanicException(
          Context.get(this).getBuiltins().error().makeNoSuchMethodError(_this, symbol), this);
    }
  }

  @Specialization
  Stateful doDataflowError(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      DataflowError _this,
      Object[] arguments,
      @Cached DataflowErrorResolverNode dataflowErrorResolverNode) {
    Function function = dataflowErrorResolverNode.execute(symbol, _this);
    if (errorReceiverProfile.profile(function == null)) {
      return new Stateful(state, _this);
    } else {
      return invokeFunctionNode.execute(function, frame, state, arguments);
    }
  }

  @Specialization
  Stateful doPanicSentinel(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      PanicSentinel _this,
      Object[] arguments) {
    throw _this;
  }

  @Specialization
  Stateful doWarning(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      WithWarnings _this,
      Object[] arguments) {
    // Cannot use @Cached for childDispatch, because we need to call notifyInserted.
    if (childDispatch == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      Lock lock = getLock();
      lock.lock();
      try {
        if (childDispatch == null) {
          childDispatch =
              insert(
                  build(
                      invokeFunctionNode.getSchema(),
                      invokeFunctionNode.getDefaultsExecutionMode(),
                      invokeFunctionNode.getArgumentsExecutionMode(),
                      thisArgumentPosition));
          childDispatch.setTailStatus(getTailStatus());
          notifyInserted(childDispatch);
        }
      } finally {
        lock.unlock();
      }
    }

    arguments[thisArgumentPosition] = _this.getValue();
    ArrayRope<Warning> warnings = _this.getReassignedWarnings(this);
    Stateful result = childDispatch.execute(frame, state, symbol, _this.getValue(), arguments);
    return new Stateful(result.getState(), WithWarnings.prependTo(result.getValue(), warnings));
  }

  @ExplodeLoop
  @Specialization(
      guards = {
        "!methods.hasFunctionalDispatch(_this)",
        "!methods.hasSpecialDispatch(_this)",
        "polyglotCallType != NOT_SUPPORTED",
        "polyglotCallType != CONVERT_TO_TEXT"
      })
  Stateful doPolyglot(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      Object _this,
      Object[] arguments,
      @CachedLibrary(limit = "10") MethodDispatchLibrary methods,
      @CachedLibrary(limit = "10") InteropLibrary interop,
      @Bind("getPolyglotCallType(_this, symbol.getName(), interop)")
          HostMethodCallNode.PolyglotCallType polyglotCallType,
      @Cached(value = "buildExecutors()") ThunkExecutorNode[] argExecutors,
      @Cached(value = "buildProfiles()", dimensions = 1) BranchProfile[] profiles,
      @Cached(value = "buildProfiles()", dimensions = 1) BranchProfile[] warningProfiles,
      @Cached BranchProfile anyWarningsProfile,
      @Cached HostMethodCallNode hostMethodCallNode) {
    Object[] args = new Object[argExecutors.length];
    boolean anyWarnings = false;
    ArrayRope<Warning> accumulatedWarnings = new ArrayRope<>();
    for (int i = 0; i < argExecutors.length; i++) {
      Stateful r = argExecutors[i].executeThunk(arguments[i + 1], state, TailStatus.NOT_TAIL);
      state = r.getState();
      args[i] = r.getValue();
      if (r.getValue() instanceof DataflowError) {
        profiles[i].enter();
        return r;
      } else if (r.getValue() instanceof WithWarnings) {
        warningProfiles[i].enter();
        anyWarnings = true;
        accumulatedWarnings =
            accumulatedWarnings.append(((WithWarnings) r.getValue()).getReassignedWarnings(this));
        args[i] = ((WithWarnings) r.getValue()).getValue();
      }
    }
    Object res = hostMethodCallNode.execute(polyglotCallType, symbol.getName(), _this, args);
    if (anyWarnings) {
      anyWarningsProfile.enter();
      res = WithWarnings.prependTo(res, accumulatedWarnings);
    }
    return new Stateful(state, res);
  }

  @Specialization(
      guards = {
        "!methods.hasFunctionalDispatch(_this)",
        "!methods.hasSpecialDispatch(_this)",
        "getPolyglotCallType(_this, symbol.getName(), interop) == CONVERT_TO_TEXT"
      })
  Stateful doConvertText(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      Object _this,
      Object[] arguments,
      @CachedLibrary(limit = "10") MethodDispatchLibrary methods,
      @CachedLibrary(limit = "1") MethodDispatchLibrary textDispatch,
      @CachedLibrary(limit = "10") InteropLibrary interop) {
    try {
      String str = interop.asString(_this);
      Text txt = Text.create(str);
      Function function = textDispatch.getFunctionalDispatch(txt, symbol);
      arguments[0] = txt;
      return invokeFunctionNode.execute(function, frame, state, arguments);
    } catch (UnsupportedMessageException e) {
      throw new IllegalStateException("Impossible, _this is guaranteed to be a string.");
    } catch (MethodDispatchLibrary.NoSuchMethodException e) {
      throw new PanicException(
          Context.get(this).getBuiltins().error().makeNoSuchMethodError(_this, symbol), this);
    }
  }

  @Specialization(
      guards = {
        "!methods.hasFunctionalDispatch(_this)",
        "!methods.hasSpecialDispatch(_this)",
        "getPolyglotCallType(_this, symbol.getName(), interop) == NOT_SUPPORTED"
      })
  Stateful doFallback(
      VirtualFrame frame,
      Object state,
      UnresolvedSymbol symbol,
      Object _this,
      Object[] arguments,
      @CachedLibrary(limit = "10") MethodDispatchLibrary methods,
      @CachedLibrary(limit = "10") InteropLibrary interop,
      @Cached AnyResolverNode anyResolverNode) {
    Function function = anyResolverNode.execute(symbol, _this);
    return invokeFunctionNode.execute(function, frame, state, arguments);
  }

  @Override
  public SourceSection getSourceSection() {
    Node parent = getParent();
    return parent == null ? null : parent.getSourceSection();
  }

  BranchProfile[] buildProfiles() {
    BranchProfile[] result = new BranchProfile[argumentCount - 1];
    for (int i = 0; i < argumentCount - 1; i++) {
      result[i] = BranchProfile.create();
    }
    return result;
  }

  ThunkExecutorNode[] buildExecutors() {
    ThunkExecutorNode[] result = new ThunkExecutorNode[argumentCount - 1];
    for (int i = 0; i < argumentCount - 1; i++) {
      result[i] = ThunkExecutorNode.build();
    }
    return result;
  }

  /**
   * Sets the expression ID of this node.
   *
   * @param id the expression ID to assign this node.
   */
  public void setId(UUID id) {
    invokeFunctionNode.setId(id);
  }
}
