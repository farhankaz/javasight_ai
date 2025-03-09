import { NextResponse } from 'next/server';
import clientPromise from '@/lib/mongodb';
import { ObjectId } from 'mongodb';

export async function GET(
  request: Request,
  context: { params: Promise<{ id: string; moduleId: string; packageId: string }> }
) {
  try {
    const params = await context.params;
    const { id, moduleId, packageId } = params;

    // Validate required parameters
    if (!id || !moduleId || !packageId) {
      console.error('Missing required parameters:', { id, moduleId, packageId });
      return NextResponse.json({ error: 'Missing required parameters' }, { status: 400 });
    }

    // Validate ObjectId format
    try {
      new ObjectId(packageId);
    } catch (err) {
      console.error('Invalid packageId format:', packageId);
      return NextResponse.json({ error: 'Invalid package ID format' }, { status: 400 });
    }

    const client = await clientPromise;
    const db = client.db('javasight');

    // Get package details
    const packageDetails = await db.collection('packages').findOne(
      { _id: new ObjectId(packageId) }
    );

    if (!packageDetails) {
      return NextResponse.json({ error: 'Package not found' }, { status: 404 });
    }

    // Get all files in the package
    const files = await db.collection('files')
      .find({
        packageId: packageId
      })
      .project({
        filePath: 1,
        linesOfCode: 1,
        shortAnalysis: 1,
        analysisTokenCount: 1,
        codeTokenCount: 1,
        _id: 1
      })
      .toArray();

    // Try to get package metrics
    let packageMetrics: { fileCount?: number; linesOfCode?: number, combinedAnalysisTokenCount?: number, codeTokenCount?: number } = {};
    try {
      const metricsDoc = await db.collection('packages_metrics').findOne(
        { packageId: packageId }
      );
      console.log('Package Metrics:', metricsDoc);
      if (metricsDoc) {
        console.log('Metrics:', metricsDoc);
        packageMetrics = {
          fileCount: metricsDoc.fileCount,
          linesOfCode: metricsDoc.linesOfCode,
          combinedAnalysisTokenCount: metricsDoc.combinedAnalysisTokenCount,
          codeTokenCount: metricsDoc.codeTokenCount
          
        };
      }
    } catch (error) {
      console.warn('Failed to retrieve package metrics:', error);
      // Continue without metrics
    }

    // Calculate file count and lines of code
    const fileCount = packageMetrics.fileCount || files.length;
    const linesOfCode = packageMetrics.linesOfCode ||
      files.reduce((total, file) => total + (typeof file.linesOfCode === 'number' ? file.linesOfCode : 0), 0);

    const response = {
      projectId: id,
      moduleId: moduleId,
      packageId: packageId,
      packageName: packageDetails.packageName,
      description: packageDetails.analysis || '',
      fileCount: fileCount,
      linesOfCode: linesOfCode,
      metrics: {
        fileCount: fileCount,
        linesOfCode: linesOfCode,
        combinedAnalysisTokenCount: packageMetrics.combinedAnalysisTokenCount,
        codeTokenCount: packageMetrics.codeTokenCount
      },
      files: files.map(file => ({
        id: file._id.toString(), // Fixed toString function call
        name: file.filePath.split('/').pop(),
        linesOfCode: file.linesOfCode,
        description: file.shortAnalysis || '',
        analysisTokenCount: file.analysisTokenCount || 0,
        codeTokenCount: file.codeTokenCount || 0
      }))
    };

    return NextResponse.json(response);
  } catch (error) {
    console.error('Error details:', {
      name: error.name,
      message: error.message,
      stack: error.stack
    });
    
    if (error instanceof Error && error.message.includes('ObjectId')) {
      return NextResponse.json({ error: 'Invalid ID format' }, { status: 400 });
    }
    return NextResponse.json({ error: 'Internal Server Error' }, { status: 500 });
  }
} 