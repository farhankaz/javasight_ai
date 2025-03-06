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
    const packageDetails = await db.collection('java_packages').findOne(
      { _id: new ObjectId(packageId) }
    );

    if (!packageDetails) {
      return NextResponse.json({ error: 'Package not found' }, { status: 404 });
    }

    // Get all files in the package
    const files = await db.collection('java_files')
      .find({ 
        projectId: id,
        moduleId: moduleId,
        packageId: packageId
      })
      .project({
        filePath: 1,
        linesOfCode: 1,
        shortAnalysis: 1
      })
      .toArray();

    const response = {
      projectId: id,
      moduleId: moduleId,
      packageId: packageId,
      packageName: packageDetails.packageName,
      description: packageDetails.analysis || '',
      fileCount: packageDetails.fileCount,
      linesOfCode: packageDetails.linesOfCode,
      files: files.map(file => ({
        id: file._id.$toString,
        name: file.filePath.split('/').pop(),
        linesOfCode: file.linesOfCode,
        description: file.shortAnalysis || ''
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