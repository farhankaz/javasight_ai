import { NextResponse } from 'next/server';
import clientPromise from '@/lib/mongodb';
import { ObjectId } from 'mongodb';

export async function GET(
  request: Request,
  context: { params: Promise<{ id: string }> }
) {
  try {
    const params = await context.params;
    const { id } = params;

    const client = await clientPromise;
    const db = client.db();

    // Get project
    const project = await db.collection('projects').findOne({
      _id: new ObjectId(id)
    });

    if (!project) {
      // Log for debugging
      console.log('Project not found for id:', id);
      return NextResponse.json({ error: 'Project not found' }, { status: 404 });
    }

    // Get project metrics - use the most recent metrics
    const projectMetrics = await db.collection('java_projects_metrics')
      .findOne(
        { projectId: id },
        { sort: { timestamp: -1 } }
      );

    // Get modules
    const modules = await db.collection('java_modules')
      .find({ projectId: id })
      .toArray();

    // Get module metrics - get the most recent metrics for each module
    const moduleMetrics = await db.collection('java_modules_metrics')
      .find({ projectId: id })
      .sort({ timestamp: -1 })
      .toArray();

    // Combine modules with their metrics
    const modulesWithMetrics = modules.map(module => {
      const metrics = moduleMetrics.find(m => m.moduleId === module._id.toString());
      return {
        ...module,
        metrics: metrics || null
      };
    });

    return NextResponse.json({
      ...project,
      id: project._id.toString(),
      metrics: projectMetrics || null,
      modules: modulesWithMetrics
    });
  } catch (error) {
    console.error('Error fetching project:', error);
    if (error instanceof Error && error.message.includes('ObjectId')) {
      return NextResponse.json({ error: 'Invalid project ID format' }, { status: 400 });
    }
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
} 