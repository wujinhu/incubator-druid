/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.math.expr;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.Comparators;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base interface of Druid expression language abstract syntax tree nodes. All {@link Expr} implementations are
 * immutable.
 */
public interface Expr
{
  /**
   * Indicates expression is a constant whose literal value can be extracted by {@link Expr#getLiteralValue()},
   * making evaluating with arguments and bindings unecessary
   */
  default boolean isLiteral()
  {
    // Overridden by things that are literals.
    return false;
  }

  /**
   * Returns the value of expr if expr is a literal, or throws an exception otherwise.
   *
   * @return {@link ConstantExpr}'s literal value
   *
   * @throws IllegalStateException if expr is not a literal
   */
  @Nullable
  default Object getLiteralValue()
  {
    // Overridden by things that are literals.
    throw new ISE("Not a literal");
  }

  /**
   * Returns the string identifier of an {@link IdentifierExpr}, else null
   */
  @Nullable
  default String getIdentifierIfIdentifier()
  {
    // overridden by things that are identifiers
    return null;
  }

  /**
   * Evaluate the {@link Expr} with the bindings which supply {@link IdentifierExpr} with their values, producing an
   * {@link ExprEval} with the result.
   */
  ExprEval eval(ObjectBinding bindings);

  /**
   * Programmatically inspect the {@link Expr} tree with a {@link Visitor}. Each {@link Expr} is responsible for
   * ensuring the {@link Visitor} can visit all of its {@link Expr} children before visiting itself.
   */
  void visit(Visitor visitor);

  /**
   * Programatically rewrite the {@link Expr} tree with a {@link Shuttle}.Each {@link Expr} is responsible for
   * ensuring the {@link Shuttle} can visit all of its {@link Expr} children, as well as updating its children
   * {@link Expr} with the results from the {@link Shuttle}, before finally visiting an updated form of itself.
   */
  Expr visit(Shuttle shuttle);

  /**
   * Examine the usage of {@link IdentifierExpr} children of an {@link Expr}, constructing a {@link BindingDetails}
   */
  BindingDetails analyzeInputs();

  /**
   * Mechanism to supply values to back {@link IdentifierExpr} during expression evaluation
   */
  interface ObjectBinding
  {
    /**
     * Get value binding for string identifier of {@link IdentifierExpr}
     */
    @Nullable
    Object get(String name);
  }

  /**
   * Mechanism to inspect an {@link Expr}, implementing a {@link Visitor} allows visiting all children of an
   * {@link Expr}
   */
  interface Visitor
  {
    /**
     * Provide the {@link Visitor} with an {@link Expr} to inspect
     */
    void visit(Expr expr);
  }

  /**
   * Mechanism to rewrite an {@link Expr}, implementing a {@link Shuttle} allows visiting all children of an
   * {@link Expr}, and replacing them as desired.
   */
  interface Shuttle
  {
    /**
     * Provide the {@link Shuttle} with an {@link Expr} to inspect and potentially rewrite.
     */
    Expr visit(Expr expr);
  }

  /**
   * Information about the context in which {@link IdentifierExpr} are used in a greater {@link Expr}, listing
   * the 'free variables' (total set of required input columns or values) and distinguishing between which identifiers
   * are used as scalar values and which are used as array values.
   */
  class BindingDetails
  {
    private final ImmutableSet<String> freeVariables;
    private final ImmutableSet<String> scalarVariables;
    private final ImmutableSet<String> arrayVariables;

    public BindingDetails()
    {
      this(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    public BindingDetails(String identifier)
    {
      this(ImmutableSet.of(identifier), Collections.emptySet(), Collections.emptySet());
    }

    public BindingDetails(Set<String> freeVariables, Set<String> scalarVariables, Set<String> arrayVariables)
    {
      this.freeVariables = ImmutableSet.copyOf(freeVariables);
      this.scalarVariables = ImmutableSet.copyOf(scalarVariables);
      this.arrayVariables = ImmutableSet.copyOf(arrayVariables);
    }

    /**
     * Get the list of required column inputs to evaluate an expression
     */
    public ImmutableList<String> getRequiredColumns()
    {
      return ImmutableList.copyOf(freeVariables);
    }

    /**
     * Total set of 'free' identifiers of an {@link Expr}, that are not supplied by a {@link LambdaExpr} binding
     */
    public ImmutableSet<String> getFreeVariables()
    {
      return freeVariables;
    }

    /**
     * Set of identifiers which are used with scalar operators and functions
     */
    public ImmutableSet<String> getScalarVariables()
    {
      return scalarVariables;
    }

    /**
     * Set of identifiers which are used with array typed functions and apply functions.
     */
    public ImmutableSet<String> getArrayVariables()
    {
      return arrayVariables;
    }

    public BindingDetails merge(BindingDetails other)
    {
      return new BindingDetails(
          Sets.union(freeVariables, other.freeVariables),
          Sets.union(scalarVariables, other.scalarVariables),
          Sets.union(arrayVariables, other.arrayVariables)
      );
    }

    public BindingDetails mergeWith(Set<String> moreScalars, Set<String> moreArrays)
    {
      return new BindingDetails(
          Sets.union(freeVariables, Sets.union(moreScalars, moreArrays)),
          Sets.union(scalarVariables, moreScalars),
          Sets.union(arrayVariables, moreArrays)
      );
    }

    public BindingDetails mergeWithScalars(Set<String> moreScalars)
    {
      return new BindingDetails(
          Sets.union(freeVariables, moreScalars),
          Sets.union(scalarVariables, moreScalars),
          arrayVariables
      );
    }

    public BindingDetails mergeWithArrays(Set<String> moreArrays)
    {
      return new BindingDetails(
          Sets.union(freeVariables, moreArrays),
          scalarVariables,
          Sets.union(arrayVariables, moreArrays)
      );
    }
  }
}

/**
 * Base type for all constant expressions. {@link ConstantExpr} allow for direct value extraction without evaluating
 * {@link Expr.ObjectBinding}. {@link ConstantExpr} are terminal nodes of an expression tree, and have no children
 * {@link Expr}.
 */
abstract class ConstantExpr implements Expr
{
  @Override
  public boolean isLiteral()
  {
    return true;
  }

  @Override
  public void visit(Visitor visitor)
  {
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    return shuttle.visit(this);
  }

  @Override
  public BindingDetails analyzeInputs()
  {
    return new BindingDetails();
  }
}

class LongExpr extends ConstantExpr
{
  private final Long value;

  LongExpr(Long value)
  {
    this.value = Preconditions.checkNotNull(value, "value");
  }

  @Override
  public Object getLiteralValue()
  {
    return value;
  }

  @Override
  public String toString()
  {
    return String.valueOf(value);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.ofLong(value);
  }
}

class LongArrayExpr extends ConstantExpr
{
  private final Long[] value;

  LongArrayExpr(Long[] value)
  {
    this.value = Preconditions.checkNotNull(value, "value");
  }

  @Override
  public Object getLiteralValue()
  {
    return value;
  }

  @Override
  public String toString()
  {
    return Arrays.toString(value);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.ofLongArray(value);
  }
}

class StringExpr extends ConstantExpr
{
  @Nullable
  private final String value;

  StringExpr(@Nullable String value)
  {
    this.value = NullHandling.emptyToNullIfNeeded(value);
  }

  @Nullable
  @Override
  public Object getLiteralValue()
  {
    return value;
  }

  @Nullable
  @Override
  public String toString()
  {
    return value;
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.of(value);
  }
}

class StringArrayExpr extends ConstantExpr
{
  private final String[] value;

  StringArrayExpr(String[] value)
  {
    this.value = Preconditions.checkNotNull(value, "value");
  }

  @Override
  public Object getLiteralValue()
  {
    return value;
  }

  @Override
  public String toString()
  {
    return Arrays.toString(value);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.ofStringArray(value);
  }
}

class DoubleExpr extends ConstantExpr
{
  private final Double value;

  DoubleExpr(Double value)
  {
    this.value = Preconditions.checkNotNull(value, "value");
  }

  @Override
  public Object getLiteralValue()
  {
    return value;
  }

  @Override
  public String toString()
  {
    return String.valueOf(value);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.ofDouble(value);
  }
}

class DoubleArrayExpr extends ConstantExpr
{
  private final Double[] value;

  DoubleArrayExpr(Double[] value)
  {
    this.value = Preconditions.checkNotNull(value, "value");
  }

  @Override
  public Object getLiteralValue()
  {
    return value;
  }

  @Override
  public String toString()
  {
    return Arrays.toString(value);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.ofDoubleArray(value);
  }
}

/**
 * This {@link Expr} node is used to represent a variable in the expression language. At evaluation time, the string
 * identifier will be used to retrieve the runtime value for the variable from {@link Expr.ObjectBinding}.
 * {@link IdentifierExpr} are terminal nodes of an expression tree, and have no children {@link Expr}.
 */
class IdentifierExpr implements Expr
{
  private final String identifier;

  IdentifierExpr(String value)
  {
    this.identifier = value;
  }

  @Override
  public String toString()
  {
    return identifier;
  }

  @Nullable
  @Override
  public String getIdentifierIfIdentifier()
  {
    return identifier;
  }

  @Override
  public BindingDetails analyzeInputs()
  {
    return new BindingDetails(identifier);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return ExprEval.bestEffortOf(bindings.get(identifier));
  }

  @Override
  public void visit(Visitor visitor)
  {
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    return shuttle.visit(this);
  }
}

class LambdaExpr implements Expr
{
  private final ImmutableList<IdentifierExpr> args;
  private final Expr expr;

  LambdaExpr(List<IdentifierExpr> args, Expr expr)
  {
    this.args = ImmutableList.copyOf(args);
    this.expr = expr;
  }

  @Override
  public String toString()
  {
    return StringUtils.format("(%s -> %s)", args, expr);
  }

  public int identifierCount()
  {
    return args.size();
  }

  @Nullable
  public String getIdentifier()
  {
    Preconditions.checkState(args.size() < 2, "LambdaExpr has multiple arguments");
    if (args.size() == 1) {
      return args.get(0).toString();
    }
    return null;
  }

  public List<String> getIdentifiers()
  {
    return args.stream().map(IdentifierExpr::toString).collect(Collectors.toList());
  }

  public ImmutableList<IdentifierExpr> getIdentifierExprs()
  {
    return args;
  }

  public Expr getExpr()
  {
    return expr;
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return expr.eval(bindings);
  }

  @Override
  public void visit(Visitor visitor)
  {
    expr.visit(visitor);
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    List<IdentifierExpr> newArgs =
        args.stream().map(arg -> (IdentifierExpr) shuttle.visit(arg)).collect(Collectors.toList());
    Expr newBody = expr.visit(shuttle);
    return shuttle.visit(new LambdaExpr(newArgs, newBody));
  }

  @Override
  public BindingDetails analyzeInputs()
  {
    final Set<String> lambdaArgs = args.stream().map(IdentifierExpr::toString).collect(Collectors.toSet());
    BindingDetails bodyDetails = expr.analyzeInputs();
    return new BindingDetails(
        Sets.difference(bodyDetails.getFreeVariables(), lambdaArgs),
        Sets.difference(bodyDetails.getScalarVariables(), lambdaArgs),
        Sets.difference(bodyDetails.getArrayVariables(), lambdaArgs)
    );
  }
}

/**
 * {@link Expr} node for a {@link Function} call. {@link FunctionExpr} has children {@link Expr} in the form of the
 * list of arguments that are passed to the {@link Function} along with the {@link Expr.ObjectBinding} when it is
 * evaluated.
 */
class FunctionExpr implements Expr
{
  final Function function;
  final String name;
  final ImmutableList<Expr> args;

  FunctionExpr(Function function, String name, List<Expr> args)
  {
    this.function = function;
    this.name = name;
    this.args = ImmutableList.copyOf(args);
    function.validateArguments(args);
  }

  @Override
  public String toString()
  {
    return StringUtils.format("(%s %s)", name, args);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return function.apply(args, bindings);
  }

  @Override
  public void visit(Visitor visitor)
  {
    for (Expr child : args) {
      child.visit(visitor);
    }
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    List<Expr> newArgs = args.stream().map(shuttle::visit).collect(Collectors.toList());
    return shuttle.visit(new FunctionExpr(function, name, newArgs));
  }

  @Override
  public BindingDetails analyzeInputs()
  {
    final Set<String> scalarVariables = new HashSet<>();
    final Set<String> arrayVariables = new HashSet<>();
    final Set<Expr> scalarArgs = function.getScalarInputs(args);
    final Set<Expr> arrayArgs = function.getArrayInputs(args);
    BindingDetails accumulator = new BindingDetails();

    for (Expr arg : args) {
      accumulator = accumulator.merge(arg.analyzeInputs());
    }
    for (Expr arg : scalarArgs) {
      String s = arg.getIdentifierIfIdentifier();
      if (s != null) {
        scalarVariables.add(s);
      }
    }
    for (Expr arg : arrayArgs) {
      String s = arg.getIdentifierIfIdentifier();
      if (s != null) {
        arrayVariables.add(s);
      }
    }
    return accumulator.mergeWith(scalarVariables, arrayVariables);
  }
}

/**
 * This {@link Expr} node is representative of an {@link ApplyFunction}, and has children in the form of a
 * {@link LambdaExpr} and the list of {@link Expr} arguments that are combined with {@link Expr.ObjectBinding} to
 * evaluate the {@link LambdaExpr}.
 */
class ApplyFunctionExpr implements Expr
{
  final ApplyFunction function;
  final String name;
  final LambdaExpr lambdaExpr;
  final ImmutableList<Expr> argsExpr;
  final BindingDetails bindingDetails;
  final BindingDetails lambdaBindingDetails;
  final ImmutableList<BindingDetails> argsBindingDetails;

  ApplyFunctionExpr(ApplyFunction function, String name, LambdaExpr expr, List<Expr> args)
  {
    this.function = function;
    this.name = name;
    this.argsExpr = ImmutableList.copyOf(args);
    this.lambdaExpr = expr;

    function.validateArguments(expr, args);

    // apply function expressions are examined during expression selector creation, so precompute and cache the
    // binding details of children
    ImmutableList.Builder<BindingDetails> argBindingDetailsBuilder = ImmutableList.builder();
    BindingDetails accumulator = new BindingDetails();
    for (Expr arg : argsExpr) {
      BindingDetails argDetails = arg.analyzeInputs();
      argBindingDetailsBuilder.add(argDetails);
      accumulator = accumulator.merge(argDetails);
    }

    final Set<String> arrayVariables = new HashSet<>();
    Set<Expr> arrayArgs = function.getArrayInputs(argsExpr);

    for (Expr arg : arrayArgs) {
      String s = arg.getIdentifierIfIdentifier();
      if (s != null) {
        arrayVariables.add(s);
      }
    }

    lambdaBindingDetails = lambdaExpr.analyzeInputs();
    bindingDetails = accumulator.merge(lambdaBindingDetails).mergeWithArrays(arrayVariables);
    argsBindingDetails = argBindingDetailsBuilder.build();
  }

  @Override
  public String toString()
  {
    return StringUtils.format("(%s %s, %s)", name, lambdaExpr, argsExpr);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    return function.apply(lambdaExpr, argsExpr, bindings);
  }

  @Override
  public void visit(Visitor visitor)
  {
    lambdaExpr.visit(visitor);
    for (Expr arg : argsExpr) {
      arg.visit(visitor);
    }
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    LambdaExpr newLambda = (LambdaExpr) lambdaExpr.visit(shuttle);
    List<Expr> newArgs = argsExpr.stream().map(shuttle::visit).collect(Collectors.toList());
    return shuttle.visit(new ApplyFunctionExpr(function, name, newLambda, newArgs));
  }

  @Override
  public BindingDetails analyzeInputs()
  {
    return bindingDetails;
  }
}

/**
 * Base type for all single argument operators, with a single {@link Expr} child for the operand.
 */
abstract class UnaryExpr implements Expr
{
  final Expr expr;

  UnaryExpr(Expr expr)
  {
    this.expr = expr;
  }

  abstract UnaryExpr copy(Expr expr);

  @Override
  public void visit(Visitor visitor)
  {
    expr.visit(visitor);
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    Expr newExpr = expr.visit(shuttle);
    if (newExpr != expr) {
      return shuttle.visit(copy(newExpr));
    }
    return shuttle.visit(this);
  }

  @Override
  public BindingDetails analyzeInputs()
  {
    // currently all unary operators only operate on scalar inputs
    final Set<String> scalars;
    final String identifierMaybe = expr.getIdentifierIfIdentifier();
    if (identifierMaybe != null) {
      scalars = ImmutableSet.of(identifierMaybe);
    } else {
      scalars = Collections.emptySet();
    }
    return expr.analyzeInputs().mergeWithScalars(scalars);
  }
}

class UnaryMinusExpr extends UnaryExpr
{
  UnaryMinusExpr(Expr expr)
  {
    super(expr);
  }

  @Override
  UnaryExpr copy(Expr expr)
  {
    return new UnaryMinusExpr(expr);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    ExprEval ret = expr.eval(bindings);
    if (NullHandling.sqlCompatible() && (ret.value() == null)) {
      return ExprEval.of(null);
    }
    if (ret.type() == ExprType.LONG) {
      return ExprEval.of(-ret.asLong());
    }
    if (ret.type() == ExprType.DOUBLE) {
      return ExprEval.of(-ret.asDouble());
    }
    throw new IAE("unsupported type " + ret.type());
  }

  @Override
  public String toString()
  {
    return StringUtils.format("-%s", expr);
  }
}

class UnaryNotExpr extends UnaryExpr
{
  UnaryNotExpr(Expr expr)
  {
    super(expr);
  }

  @Override
  UnaryExpr copy(Expr expr)
  {
    return new UnaryNotExpr(expr);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    ExprEval ret = expr.eval(bindings);
    if (NullHandling.sqlCompatible() && (ret.value() == null)) {
      return ExprEval.of(null);
    }
    // conforming to other boolean-returning binary operators
    ExprType retType = ret.type() == ExprType.DOUBLE ? ExprType.DOUBLE : ExprType.LONG;
    return ExprEval.of(!ret.asBoolean(), retType);
  }

  @Override
  public String toString()
  {
    return StringUtils.format("!%s", expr);
  }
}

/**
 * Base type for all binary operators, this {@link Expr} has two children {@link Expr} for the left and right side
 * operands.
 *
 * Note: all concrete subclass of this should have constructor with the form of <init>(String, Expr, Expr)
 * if it's not possible, just be sure Evals.binaryOp() can handle that
 */
abstract class BinaryOpExprBase implements Expr
{
  protected final String op;
  protected final Expr left;
  protected final Expr right;

  BinaryOpExprBase(String op, Expr left, Expr right)
  {
    this.op = op;
    this.left = left;
    this.right = right;
  }

  @Override
  public void visit(Visitor visitor)
  {
    left.visit(visitor);
    right.visit(visitor);
    visitor.visit(this);
  }

  @Override
  public Expr visit(Shuttle shuttle)
  {
    Expr newLeft = left.visit(shuttle);
    Expr newRight = right.visit(shuttle);
    if (left != newLeft || right != newRight) {
      return shuttle.visit(copy(newLeft, newRight));
    }
    return shuttle.visit(this);
  }

  @Override
  public String toString()
  {
    return StringUtils.format("(%s %s %s)", op, left, right);
  }

  protected abstract BinaryOpExprBase copy(Expr left, Expr right);

  @Override
  public BindingDetails analyzeInputs()
  {
    // currently all binary operators operate on scalar inputs
    final Set<String> scalars = new HashSet<>();
    final String leftIdentifer = left.getIdentifierIfIdentifier();
    final String rightIdentifier = right.getIdentifierIfIdentifier();
    if (leftIdentifer != null) {
      scalars.add(leftIdentifer);
    }
    if (rightIdentifier != null) {
      scalars.add(rightIdentifier);
    }
    return left.analyzeInputs()
               .merge(right.analyzeInputs())
               .mergeWithScalars(scalars);
  }
}

/**
 * Base class for numerical binary operators, with additional methods defined to evaluate primitive values directly
 * instead of wrapped with {@link ExprEval}
 */
abstract class BinaryEvalOpExprBase extends BinaryOpExprBase
{
  BinaryEvalOpExprBase(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    ExprEval leftVal = left.eval(bindings);
    ExprEval rightVal = right.eval(bindings);

    // Result of any Binary expressions is null if any of the argument is null.
    // e.g "select null * 2 as c;" or "select null + 1 as c;" will return null as per Standard SQL spec.
    if (NullHandling.sqlCompatible() && (leftVal.value() == null || rightVal.value() == null)) {
      return ExprEval.of(null);
    }


    if (leftVal.type() == ExprType.STRING && rightVal.type() == ExprType.STRING) {
      return evalString(leftVal.asString(), rightVal.asString());
    } else if (leftVal.type() == ExprType.LONG && rightVal.type() == ExprType.LONG) {
      if (NullHandling.sqlCompatible() && (leftVal.isNumericNull() || rightVal.isNumericNull())) {
        return ExprEval.of(null);
      }
      return ExprEval.of(evalLong(leftVal.asLong(), rightVal.asLong()));
    } else {
      if (NullHandling.sqlCompatible() && (leftVal.isNumericNull() || rightVal.isNumericNull())) {
        return ExprEval.of(null);
      }
      return ExprEval.of(evalDouble(leftVal.asDouble(), rightVal.asDouble()));
    }
  }

  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    throw new IllegalArgumentException("unsupported type " + ExprType.STRING);
  }

  protected abstract long evalLong(long left, long right);

  protected abstract double evalDouble(double left, double right);
}

class BinMinusExpr extends BinaryEvalOpExprBase
{
  BinMinusExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinMinusExpr(op, left, right);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return left - right;
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return left - right;
  }
}

class BinPowExpr extends BinaryEvalOpExprBase
{
  BinPowExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinPowExpr(op, left, right);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return LongMath.pow(left, Ints.checkedCast(right));
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return Math.pow(left, right);
  }
}

class BinMulExpr extends BinaryEvalOpExprBase
{
  BinMulExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinMulExpr(op, left, right);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return left * right;
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return left * right;
  }
}

class BinDivExpr extends BinaryEvalOpExprBase
{
  BinDivExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinDivExpr(op, left, right);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return left / right;
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return left / right;
  }
}

class BinModuloExpr extends BinaryEvalOpExprBase
{
  BinModuloExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinModuloExpr(op, left, right);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return left % right;
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return left % right;
  }
}

class BinPlusExpr extends BinaryEvalOpExprBase
{
  BinPlusExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinPlusExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(NullHandling.nullToEmptyIfNeeded(left)
                       + NullHandling.nullToEmptyIfNeeded(right));
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return left + right;
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return left + right;
  }
}

class BinLtExpr extends BinaryEvalOpExprBase
{
  BinLtExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinLtExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(Comparators.<String>naturalNullsFirst().compare(left, right) < 0, ExprType.LONG);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return Evals.asLong(left < right);
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    // Use Double.compare for more consistent NaN handling.
    return Evals.asDouble(Double.compare(left, right) < 0);
  }
}

class BinLeqExpr extends BinaryEvalOpExprBase
{
  BinLeqExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinLeqExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(Comparators.<String>naturalNullsFirst().compare(left, right) <= 0, ExprType.LONG);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return Evals.asLong(left <= right);
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    // Use Double.compare for more consistent NaN handling.
    return Evals.asDouble(Double.compare(left, right) <= 0);
  }
}

class BinGtExpr extends BinaryEvalOpExprBase
{
  BinGtExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinGtExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(Comparators.<String>naturalNullsFirst().compare(left, right) > 0, ExprType.LONG);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return Evals.asLong(left > right);
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    // Use Double.compare for more consistent NaN handling.
    return Evals.asDouble(Double.compare(left, right) > 0);
  }
}

class BinGeqExpr extends BinaryEvalOpExprBase
{
  BinGeqExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinGeqExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(Comparators.<String>naturalNullsFirst().compare(left, right) >= 0, ExprType.LONG);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return Evals.asLong(left >= right);
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    // Use Double.compare for more consistent NaN handling.
    return Evals.asDouble(Double.compare(left, right) >= 0);
  }
}

class BinEqExpr extends BinaryEvalOpExprBase
{
  BinEqExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinEqExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(Objects.equals(left, right), ExprType.LONG);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return Evals.asLong(left == right);
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return Evals.asDouble(left == right);
  }
}

class BinNeqExpr extends BinaryEvalOpExprBase
{
  BinNeqExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinNeqExpr(op, left, right);
  }

  @Override
  protected ExprEval evalString(@Nullable String left, @Nullable String right)
  {
    return ExprEval.of(!Objects.equals(left, right), ExprType.LONG);
  }

  @Override
  protected final long evalLong(long left, long right)
  {
    return Evals.asLong(left != right);
  }

  @Override
  protected final double evalDouble(double left, double right)
  {
    return Evals.asDouble(left != right);
  }
}

class BinAndExpr extends BinaryOpExprBase
{
  BinAndExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinAndExpr(op, left, right);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    ExprEval leftVal = left.eval(bindings);
    return leftVal.asBoolean() ? right.eval(bindings) : leftVal;
  }
}

class BinOrExpr extends BinaryOpExprBase
{
  BinOrExpr(String op, Expr left, Expr right)
  {
    super(op, left, right);
  }

  @Override
  protected BinaryOpExprBase copy(Expr left, Expr right)
  {
    return new BinOrExpr(op, left, right);
  }

  @Override
  public ExprEval eval(ObjectBinding bindings)
  {
    ExprEval leftVal = left.eval(bindings);
    return leftVal.asBoolean() ? leftVal : right.eval(bindings);
  }
}
