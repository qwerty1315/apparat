/*
 * This file is part of Apparat.
 * 
 * Apparat is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Apparat is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Apparat. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright (C) 2009 Joa Ebert
 * http://www.joa-ebert.com/
 * 
 */

package com.joa_ebert.apparat.taas.toolkit.inlineExpansion;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.joa_ebert.apparat.abc.AbcEnvironment;
import com.joa_ebert.apparat.abc.Method;
import com.joa_ebert.apparat.controlflow.ControlFlowGraphException;
import com.joa_ebert.apparat.controlflow.VertexKind;
import com.joa_ebert.apparat.taas.Taas;
import com.joa_ebert.apparat.taas.TaasBuilder;
import com.joa_ebert.apparat.taas.TaasCode;
import com.joa_ebert.apparat.taas.TaasEdge;
import com.joa_ebert.apparat.taas.TaasException;
import com.joa_ebert.apparat.taas.TaasLocal;
import com.joa_ebert.apparat.taas.TaasMethod;
import com.joa_ebert.apparat.taas.TaasPhi;
import com.joa_ebert.apparat.taas.TaasReference;
import com.joa_ebert.apparat.taas.TaasValue;
import com.joa_ebert.apparat.taas.TaasVertex;
import com.joa_ebert.apparat.taas.constants.TaasMultiname;
import com.joa_ebert.apparat.taas.expr.TCallProperty;
import com.joa_ebert.apparat.taas.expr.TReturn;
import com.joa_ebert.apparat.taas.toolkit.ITaasTool;
import com.joa_ebert.apparat.taas.toolkit.TaasToolkit;
import com.joa_ebert.apparat.taas.types.MultinameType;
import com.joa_ebert.apparat.taas.types.TaasType;
import com.joa_ebert.apparat.taas.types.VoidType;

public class InlineExpansion implements ITaasTool
{
	private static final class InlineTarget
	{
		public final TaasVertex vertex;
		public final TaasMethod method;

		public InlineTarget( final TaasVertex vertex, final TaasMethod method )
		{
			this.vertex = vertex;
			this.method = method;
		}
	}

	private static final Taas TAAS = new Taas();
	private final TaasBuilder builder = new TaasBuilder();
	private boolean changed;

	private TCallProperty findCall( final TaasValue value )
	{
		if( null == value )
		{
			return null;
		}

		if( value instanceof TCallProperty )
		{
			return (TCallProperty)value;
		}
		else if( value instanceof TaasPhi )
		{
			final TaasPhi phi = (TaasPhi)value;

			for( final TaasPhi.Element element : phi.values )
			{
				if( element.value != null
						&& element.value instanceof TCallProperty )
				{
					return (TCallProperty)element.value;
				}

				final TCallProperty result = findCall( element.value );

				if( null != result )
				{
					return result;
				}
			}

			return null;
		}
		else
		{
			final Field[] fields = value.getClass().getFields();

			for( final Field field : fields )
			{
				if( field.isAnnotationPresent( TaasReference.class ) )
				{
					try
					{
						final Object referencedObject = field.get( value );

						if( referencedObject instanceof TaasValue )
						{
							final TaasValue referenced = (TaasValue)referencedObject;

							if( null != referenced
									&& referenced instanceof TCallProperty )
							{
								return (TCallProperty)referenced;
							}

							final TCallProperty result = findCall( referenced );

							if( null != result )
							{
								return result;
							}
						}
						else if( referencedObject instanceof TaasValue[] )
						{
							final TaasValue[] referenced = (TaasValue[])referencedObject;

							if( null != referenced )
							{
								for( final TaasValue referencedValue : referenced )
								{
									if( null != referencedValue
											&& referencedValue instanceof TCallProperty )
									{
										return (TCallProperty)referencedValue;
									}

									final TCallProperty result = findCall( referencedValue );

									if( null != result )
									{
										return result;
									}
								}
							}
						}
					}
					catch( final Exception e )
					{
						throw new TaasException( e );
					}
				}
			}

			return null;
		}
	}

	private void inline( final TaasMethod targetMethod,
			final TaasVertex insertionVertex, final TaasMethod inlinedMethod )
	{
		try
		{
			final TaasCode targetCode = targetMethod.code;

			final List<TaasLocal> registersToContribute = inlinedMethod.locals
					.getRegisterList();

			//
			// We do not want to add the first register since it stores only
			// the scope object. So we can drop it here.
			//

			for( int i = 1, n = registersToContribute.size(); i < n; ++i )
			{
				targetMethod.locals.add( registersToContribute.get( i ) );
			}

			//
			// Now copy all vertices and edges into the method.
			// We ignore of course the start and end vertex.
			//

			final TaasCode inlinedCode = inlinedMethod.code;

			for( final TaasVertex inlinedVertex : inlinedCode.vertexList() )
			{
				if( VertexKind.Default != inlinedVertex.kind )
				{
					continue;
				}

				targetCode.add( inlinedVertex );
			}

			for( final TaasEdge inlinedEdge : inlinedCode.edgeList() )
			{
				if( inlinedEdge.startVertex.kind == VertexKind.Default
						&& inlinedEdge.endVertex.kind == VertexKind.Default )
				{
					targetCode.add( inlinedEdge );
				}
			}

			//
			// Now we only have to add the proper connections of the source and
			// the sink into the new method.
			//

			List<TaasEdge> incommingOf = targetCode
					.incommingOf( insertionVertex );

			final TaasVertex sourceVertex = inlinedCode.outgoingOf(
					inlinedCode.getEntryVertex() ).get( 0 ).endVertex;

			for( final TaasEdge edge : incommingOf )
			{
				edge.endVertex = sourceVertex;
			}

			incommingOf = inlinedCode.incommingOf( inlinedCode.getExitVertex() );

			for( final TaasEdge edge : incommingOf )
			{
				targetCode
						.add( new TaasEdge( edge.startVertex, insertionVertex ) );
			}
		}
		catch( final ControlFlowGraphException exception )
		{
			exception.printStackTrace();
		}
	}

	public boolean manipulate( final AbcEnvironment environment,
			final TaasMethod method )
	{
		changed = false;

		final TaasCode code = method.code;
		final List<TaasVertex> vertices = code.vertexList();
		final List<InlineTarget> targets = new LinkedList<InlineTarget>();

		for( final TaasVertex vertex : vertices )
		{
			final TaasValue value = vertex.value;
			final TCallProperty callProperty = findCall( value );

			//
			// Inline TCallProperty expressions:
			//

			if( null != callProperty )
			{
				final TaasValue object = callProperty.object;
				final TaasMultiname property = callProperty.property;

				if( object instanceof TaasLocal )
				{
					final TaasLocal local = (TaasLocal)object;

					if( 0 == local.getIndex() )
					{
						if( !( local.getType() instanceof MultinameType ) )
						{
							//
							// We support only typed objects.
							//

							continue;
						}

						if( !( property.getType() instanceof MultinameType ) )
						{
							//
							// We support only typed properties.
							//

							continue;
						}

						final MultinameType mobj = (MultinameType)local
								.getType();
						final MultinameType mprp = (MultinameType)property
								.getType();

						if( mobj.runtimeName != null
								|| mprp.runtimeName != null )
						{
							//
							// We do not touch anything that may be changed
							// during runtime.
							//

							continue;
						}

						final Method abcMethod = method.typer.findProperty(
								mobj, mprp );

						if( null == abcMethod )
						{
							//
							// Typer could not find method.
							//

							continue;
						}

						if( null == abcMethod.body
								|| null == abcMethod.body.code )
						{
							//
							// External or native method.
							//

							continue;
						}

						final TaasMethod inlinedMethod = builder.build(
								environment, abcMethod.body.code );
						final TaasCode inlinedCode = inlinedMethod.code;

						//
						// Shift the register indices so that we have no clash.
						//

						final int offset = method.locals.numRegisters();
						inlinedMethod.locals.offset( offset );

						final TaasType returnType = method.typer
								.toNativeType( abcMethod.returnType );

						//
						// We have to set the local variables according to their
						// parameters.
						//
						// So we search for an insertion point to set those
						// local variables with values of the parameters.
						//

						TaasVertex insertPoint = null;

						try
						{
							insertPoint = inlinedCode.outgoingOf(
									inlinedCode.getEntryVertex() ).get( 0 ).endVertex;
						}
						catch( final ControlFlowGraphException exception )
						{
							throw new TaasException( exception );
						}

						//
						// We ignore the local at index 0 since it stores only
						// the scope object.
						//

						int localIndex = 1;

						for( final TaasValue param : callProperty.parameters )
						{
							//
							// Set the local variable with the value of the
							// parameter.
							//

							TaasToolkit
									.insertBefore(
											inlinedMethod,
											insertPoint,
											new TaasVertex(
													TAAS
															.setLocal(
																	inlinedMethod.locals
																			.get( offset
																					+ localIndex++ ),
																	param ) ) );
						}

						if( returnType == VoidType.INSTANCE )
						{
							// TODO
						}
						else
						{
							//
							// Since we return a value we need a register to
							// store the result.
							//

							final TaasLocal result = TaasToolkit
									.createRegister( method );

							//
							// Also, type the register here with the return type
							// of the inlined method.
							//

							result.typeAs( returnType );

							//
							// Next: Replace all TReturn expressions with a
							// TSetLocal instead.
							//

							final LinkedList<TaasVertex> inlinedVertices = inlinedCode
									.vertexList();

							final Map<TaasValue, TaasValue> replacements = new LinkedHashMap<TaasValue, TaasValue>();

							for( final TaasVertex inlinedVertex : inlinedVertices )
							{
								if( VertexKind.Default != inlinedVertex.kind )
								{
									continue;
								}

								final TaasValue newValue = inlinedVertex.value;

								if( newValue instanceof TReturn )
								{
									replacements
											.put(
													newValue,
													TAAS
															.setLocal(
																	result,
																	( (TReturn)newValue ).value ) );
								}
							}

							for( final Entry<TaasValue, TaasValue> replacement : replacements
									.entrySet() )
							{
								TaasToolkit.replace( inlinedMethod, replacement
										.getKey(), replacement.getValue() );
							}

							//
							// And finally, we replace the call expression with
							// the new resulting register.
							//

							TaasToolkit.replace( value, callProperty, result );

							//
							// In order to avoid a concurrent modification while
							// traversing the list we will now save the
							// information of what to do here.
							//

							targets.add( new InlineTarget( vertex,
									inlinedMethod ) );
						}
					}
				}

			}
		}

		//
		// Inline all methods now.
		//

		for( final InlineTarget target : targets )
		{
			inline( method, target.vertex, target.method );
		}

		return changed;
	}
}
